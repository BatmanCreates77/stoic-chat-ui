package com.screenmeter.app

import com.screenmeter.app.measure.Luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuminanceTest {
    @Test
    fun agreesWithClassicExposureEquation() {
        // N^2 / t = L * S / K with K = 12.5 renders L as roughly 12 % of full scale.
        val n = 1.7; val s = 50; val l = 250.0
        val t = n * n / (l * s / 12.5)
        val estimate = Luminance.nits(0.12, (t * 1e9).toLong(), s, n)
        assertTrue("estimate $estimate should be within 15 % of $l", Math.abs(estimate - l) / l < 0.15)
    }

    @Test
    fun scalesLinearlyWithSignalAndInverselyWithExposure() {
        val a = Luminance.nits(0.2, 10_000_000L, 100, 1.8)
        val b = Luminance.nits(0.4, 10_000_000L, 100, 1.8)
        val c = Luminance.nits(0.2, 20_000_000L, 100, 1.8)
        assertEquals(2.0, b / a, 1e-9)
        assertEquals(0.5, c / a, 1e-9)
    }

    @Test
    fun calibrationFactorApplies() {
        assertEquals(Luminance.nits(1.0, 1.8) * 1.25, Luminance.nits(1.0, 1.8, 1.25), 1e-9)
        assertEquals(0.0, Luminance.nits(0.0, 1.8), 0.0)
    }
}
