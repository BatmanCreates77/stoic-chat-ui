package com.screenmeter.app.color

import android.hardware.camera2.params.ColorSpaceTransform
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Minimal 3x3 matrix helpers for sensor -> CIE XYZ conversion. */
class Mat3(val m: DoubleArray) {
    init { require(m.size == 9) }

    operator fun times(v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += m[i * 3 + k] * o.m[k * 3 + j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    fun inverse(): Mat3? {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return null
        val inv = DoubleArray(9)
        inv[0] = (a[4] * a[8] - a[5] * a[7]) / det
        inv[1] = (a[2] * a[7] - a[1] * a[8]) / det
        inv[2] = (a[1] * a[5] - a[2] * a[4]) / det
        inv[3] = (a[5] * a[6] - a[3] * a[8]) / det
        inv[4] = (a[0] * a[8] - a[2] * a[6]) / det
        inv[5] = (a[2] * a[3] - a[0] * a[5]) / det
        inv[6] = (a[3] * a[7] - a[4] * a[6]) / det
        inv[7] = (a[1] * a[6] - a[0] * a[7]) / det
        inv[8] = (a[0] * a[4] - a[1] * a[3]) / det
        return Mat3(inv)
    }

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** Linear sRGB (D65) -> XYZ. Used as a fallback when the camera exposes no colour matrices. */
        val SRGB_TO_XYZ = Mat3(
            doubleArrayOf(
                0.4124564, 0.3575761, 0.1804375,
                0.2126729, 0.7151522, 0.0721750,
                0.0193339, 0.1191920, 0.9503041,
            )
        )

        /** Camera2 ColorSpaceTransform is addressed as getElement(column, row). */
        fun from(t: ColorSpaceTransform): Mat3 {
            val d = DoubleArray(9)
            for (row in 0..2) for (col in 0..2) {
                val r = t.getElement(col, row)
                d[row * 3 + col] = r.numerator.toDouble() / r.denominator.toDouble()
            }
            return Mat3(d)
        }
    }
}

data class Chromaticity(val x: Double, val y: Double, val uPrime: Double, val vPrime: Double)

object ColorMath {
    fun chromaticity(xyz: DoubleArray): Chromaticity {
        val sum = xyz[0] + xyz[1] + xyz[2]
        if (sum <= 0.0) return Chromaticity(0.0, 0.0, 0.0, 0.0)
        val x = xyz[0] / sum
        val y = xyz[1] / sum
        val den = -2 * x + 12 * y + 3
        return Chromaticity(x, y, 4 * x / den, 9 * y / den)
    }

    /** McCamy's approximation of correlated colour temperature from CIE xy. */
    fun cct(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449.0 * n.pow(3) + 3525.0 * n.pow(2) + 6823.3 * n + 5520.33
    }

    /** Delta u'v' between two chromaticities; 0.0033 is roughly one just-noticeable difference. */
    fun deltaUv(a: Chromaticity, b: Chromaticity): Double =
        sqrt((a.uPrime - b.uPrime).pow(2) + (a.vPrime - b.vPrime).pow(2))

    /** Effective gamma of one grey step: log(Y/Ywhite) / log(level/255). */
    fun stepGamma(relLuminance: Double, level: Int): Double? {
        if (level <= 0 || level >= 255 || relLuminance <= 0.0) return null
        return ln(relLuminance) / ln(level / 255.0)
    }

    fun srgbEncodedToLinear(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}
