/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.translate.intrinsic.functions.factories

import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.js.backend.ast.metadata.SpecialFunction
import org.jetbrains.kotlin.js.backend.ast.metadata.specialFunction
import org.jetbrains.kotlin.js.translate.callTranslator.CallInfo
import org.jetbrains.kotlin.js.translate.context.Namer
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.expression.ExpressionVisitor
import org.jetbrains.kotlin.js.translate.intrinsic.functions.basic.FunctionIntrinsic
import org.jetbrains.kotlin.js.translate.utils.getReferenceToJsClass
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.isRecursiveInlineClassType
import org.jetbrains.kotlin.types.*

object TypeOfFIF : FunctionIntrinsicFactory {
    override fun getIntrinsic(descriptor: FunctionDescriptor, context: TranslationContext): FunctionIntrinsic? =
        Intrinsic.takeIf { descriptor.fqNameUnsafe.asString() == "kotlin.reflect.typeOf" }

    object Intrinsic : FunctionIntrinsic() {
        override fun apply(callInfo: CallInfo, arguments: List<JsExpression>, context: TranslationContext): JsExpression {
            val type = callInfo.resolvedCall.typeArguments.values.single()
            return KTypeConstructor(context).createKType(type)
        }
    }
}

class KTypeConstructor(val context: TranslationContext) {
//    private val cache = mutableMapOf<KotlinType, Box<JsExpression>>()
//    private val cache = mutableMapOf<KotlinType, JsExpression>()
    // createType(createType())

    fun callHelperFunction(name: String, vararg arguments: JsExpression) =
        JsInvocation(context.getReferenceToIntrinsic(name), *arguments)

    fun createKType(type: KotlinType): JsExpression = createKType(type, hashSetOf())
    private fun createKType(type: KotlinType, visited: MutableSet<TypeParameterDescriptor>): JsExpression {
        val unwrappedType = type.unwrap()
//        if (!visited.add(unwrappedType)) return JsNullLiteral()

        return when (unwrappedType) {
            is SimpleType -> createSimpleKType(unwrappedType, visited)
            is DynamicType -> createDynamicType()
            else -> error("Unexpected type $type")
        }
    }

    private fun createDynamicType(): JsExpression {
        return callHelperFunction(Namer.CREATE_DYNAMIC_KTYPE)
    }

    private fun createSimpleKType(type: SimpleType, visited: MutableSet<TypeParameterDescriptor>): JsExpression {
        val typeConstructor = type.constructor
        val classifier = typeConstructor.declarationDescriptor

        if (classifier is TypeParameterDescriptor && classifier.isReified) {
            val kClassName = context.getNameForIntrinsic(SpecialFunction.GET_REIFIED_TYPE_PARAMETER_KTYPE.suggestedName)
            kClassName.specialFunction = SpecialFunction.GET_REIFIED_TYPE_PARAMETER_KTYPE

            val reifiedTypeParameterType = JsInvocation(kClassName.makeRef(), getReferenceToJsClass(classifier, context))
            if (type.isMarkedNullable) {
                return callHelperFunction(Namer.MARK_KTYPE_NULLABLE, reifiedTypeParameterType)
            }

            return reifiedTypeParameterType
        }

        val kClassifier =
            when {
                classifier != null -> {
                    createKClassifier(classifier, visited)
                }
                typeConstructor is IntersectionTypeConstructor -> {
                    val getKClassA = context.getNameForIntrinsic("getKClassM")
                    val args = JsArrayLiteral(
                        typeConstructor.supertypes.map { getReferenceToJsClass(it.constructor.declarationDescriptor, context) }
                    )

                    JsInvocation(getKClassA.makeRef(), args)
                }
                else -> {
                    error("Can't get KClass from $type")
                }
            }
        val arguments = JsArrayLiteral(type.arguments.map { createKTypeProjection(it, visited) })
        val isMarkedNullable = JsBooleanLiteral(type.isMarkedNullable)
        return callHelperFunction(
            Namer.CREATE_KTYPE,
            kClassifier,
            arguments,
            isMarkedNullable
        )
    }

    private fun createKTypeProjection(tp: TypeProjection, visited: MutableSet<TypeParameterDescriptor>): JsExpression {
        if (tp.isStarProjection/* || tp.type in visited*/) {
            return callHelperFunction(Namer.GET_START_KTYPE_PROJECTION)
        }

        val factoryName = when (tp.projectionKind) {
            Variance.INVARIANT -> Namer.CREATE_INVARIANT_KTYPE_PROJECTION
            Variance.IN_VARIANCE -> Namer.CREATE_CONTRAVARIANT_KTYPE_PROJECTION
            Variance.OUT_VARIANCE -> Namer.CREATE_COVARIANT_KTYPE_PROJECTION
        }

        val kType = createKType(tp.type, visited)
        return callHelperFunction(factoryName, kType)

    }

    private fun createKClassifier(classifier: ClassifierDescriptor, visited: MutableSet<TypeParameterDescriptor>): JsExpression =
        when (classifier) {
            is TypeParameterDescriptor -> createKTypeParameter(classifier, visited)
            else -> ExpressionVisitor.getObjectKClass(context, classifier)
        }

    private fun createKTypeParameter(typeParameter: TypeParameterDescriptor, visited: MutableSet<TypeParameterDescriptor>): JsExpression {
        fun process(): JsExpression {
            val name = JsStringLiteral(typeParameter.name.asString())
            val upperBounds = JsArrayLiteral(typeParameter.upperBounds.map { createKType(it, visited) })
            val variance = when (typeParameter.variance) {
                // TODO use consts, enums, numbers???
                Variance.INVARIANT -> JsStringLiteral("invariant")
                Variance.IN_VARIANCE -> JsStringLiteral("in")
                Variance.OUT_VARIANCE -> JsStringLiteral("out")
            }
            if (typeParameter.isReified) {
                val kClassName = context.getNameForIntrinsic(SpecialFunction.GET_REIFIED_TYPE_PARAMETER_KTYPE.suggestedName)
                kClassName.specialFunction = SpecialFunction.GET_REIFIED_TYPE_PARAMETER_KTYPE

                return JsInvocation(kClassName.makeRef(), getReferenceToJsClass(typeParameter, context))
            }

            return callHelperFunction(
                Namer.CREATE_KTYPE_PARAMETER,
                name,
                upperBounds,
                variance
            )
        }

        if (typeParameter in visited) {
            return callHelperFunction(Namer.GET_START_KTYPE_PROJECTION)
        }

        visited.add(typeParameter)
        val r = process()
        visited.remove(typeParameter)

        return r
    }
}
