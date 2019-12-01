/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// TODO move to internal?
package kotlin.js

// TODO use top level declarations instead of object?
// TODO check how well DCE works with property initializers inside objects?
private external class ArrayBuffer(size: Int)
private external class Float64Array(buffer: ArrayBuffer)
private external class Float32Array(buffer: ArrayBuffer)
private external class Int32Array(buffer: ArrayBuffer)

private val buf = ArrayBuffer(8)
private val bufFloat64 = Float64Array(buf).unsafeCast<DoubleArray>()
private val bufFloat32 = Float32Array(buf).unsafeCast<FloatArray>()
private val bufInt32 = Int32Array(buf).unsafeCast<IntArray>()
// TODO use view
//  private val view = DataView(buf)

private val lowIndex = run {
    bufFloat64[0] = -1.0  // bff00000_00000000
    if (bufInt32[0] != 0) 1 else 0
}
private val highIndex = 1 - lowIndex

internal fun doubleToRawBits(value: Double): Long {
    bufFloat64[0] = value
    return Long(bufInt32[lowIndex], bufInt32[highIndex])
}

internal fun doubleFromBits(value: Long): Double {
    bufInt32[lowIndex] = value.low
    bufInt32[highIndex] = value.high
    return bufFloat64[0]
}

internal fun floatToRawBits(value: Float): Int {
    bufFloat32[0] = value
    return bufInt32[0]
}

internal fun floatFromBits(value: Int): Float {
    bufInt32[0] = value
    return bufFloat32[0]
}

// returns zero value for number with positive sign bit and non-zero value for number with negative sign bit.
internal fun doubleSignBit(value: Double): Int {
    bufFloat64[0] = value
    return bufInt32[highIndex] and Int.MIN_VALUE
}

internal fun getNumberHashCode(obj: Double): Int {
    if (jsBitwiseOr(obj, 0).unsafeCast<Double>() === obj) {
        return obj.toInt()
    }

    bufFloat64[0] = obj
    return bufInt32[highIndex] * 31 + bufInt32[lowIndex]
}
