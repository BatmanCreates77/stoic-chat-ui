package com.screenmeter.app

import com.screenmeter.app.color.ColorMath
import com.screenmeter.app.color.Mat3
import com.screenmeter.app.measure.TestSuite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {
    @Test
    fun d65WhiteHasExpectedChromaticityAndCct() {
        val xyz = Mat3.SRGB_TO_XYZ * doubleArrayOf(1.0, 1.0, 1.0)
        val c = ColorMath.chromaticity(xyz)
        assertEquals(0.3127, c.x, 0.001)
        assertEquals(0.3290, c.y, 0.001)
        assertEquals(6500.0, ColorMath.cct(c.x, c.y), 60.0)
    }

    @Test
    fun matrixInverseRoundTrips() {
        val inv = Mat3.SRGB_TO_XYZ.inverse()
        assertNotNull(inv)
        val id = inv!! * Mat3.SRGB_TO_XYZ
        for (i in 0..2) for (j in 0..2) assertEquals(if (i == j) 1.0 else 0.0, id.m[i * 3 + j], 1e-6)
    }

    @Test
    fun stepGammaRecoversPowerLaw() {
        val g = ColorMath.stepGamma(Math.pow(128.0 / 255.0, 2.2), 128)!!
        assertEquals(2.2, g, 1e-9)
        assertTrue(ColorMath.stepGamma(0.5, 0) == null && ColorMath.stepGamma(0.5, 255) == null)
    }

    @Test
    fun suitesAreWellFormed() {
        val full = TestSuite.full(includeUniformity = true)
        assertEquals(full.size, full.map { it.id }.toSet().size)
        assertTrue(full.first().id == "white" && full.last().group == "flicker")
        assertEquals(9, full.count { it.group == "uniformity" })
        assertTrue(TestSuite.quick().size < TestSuite.full(false).size)
    }
}
