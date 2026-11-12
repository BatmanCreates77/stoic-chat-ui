package com.screenmeter.app.measure

/** Absolute luminance from camera exposure data. Pure function so it can be unit tested. */
object Luminance {
    const val LENS_TRANSMISSION = 0.9
    const val ISO_SATURATION_CONSTANT = 78.0 // ISO 12232 saturation-based speed: H_sat = 78 / S lux*s

    /**
     * @param greenNorm sensor fraction of full scale divided by (exposure seconds * ISO / 100)
     * @param apertureN lens f-number
     * @param calibration user factor, 1.0 = first principles
     * @return luminance in cd/m2
     */
    fun nits(greenNorm: Double, apertureN: Double, calibration: Double = 1.0): Double {
        if (greenNorm <= 0.0 || apertureN <= 0.0) return 0.0
        return greenNorm / 100.0 * (4.0 * apertureN * apertureN * ISO_SATURATION_CONSTANT) / (Math.PI * LENS_TRANSMISSION) * calibration
    }

    /** Convenience: luminance from raw quantities. */
    fun nits(fractionOfFullScale: Double, exposureNs: Long, iso: Int, apertureN: Double, calibration: Double = 1.0): Double {
        val greenNorm = fractionOfFullScale / (exposureNs / 1e9 * iso / 100.0)
        return nits(greenNorm, apertureN, calibration)
    }
}
