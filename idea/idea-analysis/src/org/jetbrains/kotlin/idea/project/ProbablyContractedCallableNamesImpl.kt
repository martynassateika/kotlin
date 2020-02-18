/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.kotlin.idea.caches.trackers.KotlinCodeBlockModificationListener
import org.jetbrains.kotlin.idea.stubindex.KotlinProbablyContractedFunctionShortNameIndex
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.runReadActionInSmartMode
import org.jetbrains.kotlin.resolve.lazy.ProbablyContractedCallableNames

class ProbablyContractedCallableNamesImpl(project: Project) : ProbablyContractedCallableNames {
    private val functionNames = CachedValuesManager.getManager(project).createCachedValue(
        {
            val outOfCodeBlockTracker = runReadAction {
                KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker
            }
            CachedValueProvider.Result.create(
                project.runReadActionInSmartMode {
                    KotlinProbablyContractedFunctionShortNameIndex.getInstance().getAllKeys(project)
                },
                outOfCodeBlockTracker
            )
        },
        false
    )

    override fun isProbablyContractedCallableName(name: String): Boolean = name in functionNames.value
}