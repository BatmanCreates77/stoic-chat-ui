package com.screenmeter.app.camera

/**
 * Statistics extracted from one capture, already corrected for black and white level
 * and expressed as a fraction of sensor full scale (0..1).
 */
data class RawStats(
    val format: Int,
    val width: Int,
    val height: Int,
    /** Mean of each colour channel inside the ROI, black-subtracted and normalised to white level. */
    val mean: DoubleArray,
    /** Per-pixel standard deviation of each channel inside the ROI (same units as mean). */
    val std: DoubleArray,
    /** Number of ROI samples per channel. */
    val count: IntArray,
    /** Highest normalised sample seen in the ROI; > ~0.9 means clipping is likely. */
    val maxFraction: Double,
    /** Standard deviation of row means divided by overall mean. PWM banding shows up here. */
    val rowModulation: Double,
    val isRaw: Boolean,
) {
    val green: Double get() = mean[1]
}
