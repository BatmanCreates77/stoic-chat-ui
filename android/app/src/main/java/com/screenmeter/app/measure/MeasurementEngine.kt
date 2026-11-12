package com.screenmeter.app.measure

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.screenmeter.app.camera.CameraController
import com.screenmeter.app.camera.RawAnalyzer
import com.screenmeter.app.camera.RawStats
import com.screenmeter.app.color.ColorMath
import com.screenmeter.app.color.Chromaticity
import com.screenmeter.app.color.Mat3
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * One measured patch. All luminance-like quantities are in "camera relative units":
 * normalised sensor signal divided by exposure time (seconds) and ISO/100, so patches captured
 * with different exposures are directly comparable. They are NOT cd/m2 unless the user supplies a
 * scale factor from a real meter.
 */
data class Measurement(
    val patchId: String,
    val group: String,
    val zone: Int,
    val rgbIn: IntArray,
    val camRgb: DoubleArray,      // exposure-normalised camera RGB
    val xyz: DoubleArray,         // exposure-normalised XYZ (relative)
    val chroma: Chromaticity,
    val exposureNs: Long,
    val iso: Int,
    val saturated: Boolean,
    val atNoiseFloor: Boolean,
    val snr: Double,
    val rowModulation: Double,
    val flickerIndex: Double?,    // only for the flicker patch
    val isRaw: Boolean,
    val frames: Int,
    /** Absolute luminance estimate in cd/m2 from the exposure equation (see [MeasurementEngine.nitsFrom]). */
    val nits: Double,
    val aperture: Double,
    val calibration: Double,
) {
    val y: Double get() = xyz[1]

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "measure")
        put("id", patchId); put("group", group); put("zone", zone)
        put("rgb", JSONArray(rgbIn.toList()))
        put("camRgb", JSONArray(camRgb.map { it }))
        put("xyz", JSONArray(xyz.map { it }))
        put("Y", y)
        put("x", chroma.x); put("y_", chroma.y); put("u", chroma.uPrime); put("v", chroma.vPrime)
        put("exposureNs", exposureNs); put("iso", iso)
        put("saturated", saturated); put("atNoiseFloor", atNoiseFloor); put("snr", snr)
        put("rowModulation", rowModulation)
        if (flickerIndex != null) put("flickerIndex", flickerIndex)
        put("isRaw", isRaw); put("frames", frames)
        put("nits", nits); put("aperture", aperture); put("calibration", calibration)
    }
}

class MeasurementEngine(private val camera: CameraController) {

    /**
     * User calibration factor applied to every nits value. 1.0 = first principles only.
     * Set it by measuring a screen of known luminance once; it then corrects for this phone's
     * lens transmission, ISO convention and sensor tolerances together.
     */
    @Volatile var calibration: Double = 1.0

    private val aperture: Double by lazy {
        camera.characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.toDouble() ?: 1.8
    }

    /**
     * Luminance from the photographic exposure equation.
     *
     * Sensor-plane illuminance for an extended source of luminance L seen through a lens of
     * f-number N and transmission T is E = pi * L * T / (4 N^2). Saturation-based ISO speed
     * (ISO 12232) defines the exposure that just saturates the sensor as H_sat = 78 / S lux*s,
     * so a pixel at fraction v of full scale received H = v * 78 / S = E * t. Solving for L:
     *   L = 4 N^2 * v * 78 / (pi * T * S * t)
     * [greenNorm] is v / (t * S / 100), the exposure-normalised value this engine already keeps.
     * Lens transmission is taken as 0.9. Expect +-35 % uncalibrated: Android does not say which
     * ISO 12232 convention a vendor used (SOS vs saturation differ by ~1.4x) and panels are not
     * perfectly Lambertian. One calibration against a known screen removes most of that.
     */
    fun nitsFrom(greenNorm: Double, apertureN: Double = aperture): Double = Luminance.nits(greenNorm, apertureN, calibration)

    /** Matrix taking exposure-normalised camera RGB to CIE XYZ. */
    private val camToXyz: Mat3 by lazy {
        val chars = camera.characteristics
        val ill2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
        val cm2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        val cm1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        // Prefer the D65 matrix: displays are specified against D65 and the second illuminant usually is D65 (21).
        val cm = when {
            cm2 != null && ill2?.toInt() == CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D65 -> cm2
            cm1 != null -> cm1
            else -> cm2
        }
        cm?.let { Mat3.from(it).inverse() } ?: Mat3.SRGB_TO_XYZ
    }

    val usingCameraMatrix: Boolean get() = camera.characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null

    private val minExp: Long get() = camera.capabilities.exposureRangeNs?.lower ?: 100_000L
    private val maxExp: Long get() = min(camera.capabilities.exposureRangeNs?.upper ?: 250_000_000L, 250_000_000L)
    private val baseIso: Int get() = camera.capabilities.isoRange?.lower ?: 100

    private var lastExposure: Long = 20_000_000L

    private suspend fun shoot(expNs: Long, iso: Int): Pair<RawStats, CaptureResult> {
        val cap = camera.capture(expNs, iso)
        try {
            val stats = RawAnalyzer.analyze(cap.image, cap.result, camera.characteristics)
            return stats to cap.result
        } finally {
            cap.image.close()
        }
    }

    /**
     * Auto-ranging exposure: aim for the green channel between 15% and 70% of full scale with no
     * clipping, keeping ISO at its minimum so the signal stays linear and noise low.
     */
    private var lastResult: CaptureResult? = null

    private suspend fun rangeExposure(startNs: Long): Pair<RawStats, Long> {
        var exp = startNs.coerceIn(minExp, maxExp)
        var shot = shoot(exp, baseIso)
        var stats: RawStats = shot.first
        lastResult = shot.second
        for (i in 0 until 7) {
            val g = stats.green
            val next = when {
                stats.maxFraction > 0.85 -> exp / 4
                g < 0.02 -> exp * 8
                g < 0.15 -> exp * 3
                g > 0.70 -> exp / 2
                else -> exp
            }.coerceIn(minExp, maxExp)
            if (next == exp) break
            exp = next
            shot = shoot(exp, baseIso)
            stats = shot.first
            lastResult = shot.second
        }
        return stats to exp
    }

    private fun normalise(stats: RawStats, expNs: Long, iso: Int): DoubleArray {
        val expS = expNs / 1e9
        val gain = iso / 100.0
        return DoubleArray(3) { stats.mean[it] / (expS * gain) }
    }

    suspend fun measure(patch: Patch): Measurement {
        val (first, exp) = rangeExposure(lastExposure)
        val firstResult = lastResult
        lastExposure = exp
        val iso = baseIso

        if (patch.group == "flicker") return measureFlicker(patch, first, exp, iso)

        var acc = normalise(first, exp, iso)
        var rowMod = first.rowModulation
        var satur = first.maxFraction > 0.95
        var frames = 1
        for (i in 1 until patch.repeats) {
            val s = shoot(exp, iso).first
            val n = normalise(s, exp, iso)
            for (c in 0..2) acc[c] += n[c]
            rowMod += s.rowModulation
            satur = satur || s.maxFraction > 0.95
            frames++
        }
        acc = DoubleArray(3) { acc[it] / frames }
        rowMod /= frames

        val xyz = camToXyz * acc
        val chroma = ColorMath.chromaticity(xyz)
        // SNR of the mean over the ROI: per-pixel std shrinks by sqrt(samples).
        val gStd = first.std[1] / Math.sqrt(max(1, first.count[1]).toDouble())
        val snr = if (gStd > 0) first.mean[1] / gStd else Double.POSITIVE_INFINITY
        val noiseFloor = first.mean[1] < 3 * first.std[1] / Math.sqrt(max(1, first.count[1]).toDouble()) || first.mean[1] <= 0.0

        val n = resultAperture(firstResult) ?: aperture
        return Measurement(
            patch.id, patch.group, patch.zone, intArrayOf(patch.r, patch.g, patch.b),
            acc, xyz, chroma, exp, iso, satur, noiseFloor, snr, rowMod, null, first.isRaw, frames,
            nitsFrom(acc[1], n), n, calibration,
        )
    }

    private fun resultAperture(r: CaptureResult?): Double? = r?.get(CaptureResult.LENS_APERTURE)?.toDouble()

    /**
     * Flicker test: short exposures sample different phases of a PWM backlight. Both the spread of
     * whole-frame means across captures and the within-frame row banding reveal modulation.
     */
    private suspend fun measureFlicker(patch: Patch, ranged: RawStats, rangedExp: Long, iso: Int): Measurement {
        val shortExp = max(minExp, min(rangedExp / 16, 1_000_000L)) // <= 1 ms, as short as the sensor allows
        val means = mutableListOf<Double>()
        var rowMod = 0.0
        for (i in 0 until patch.repeats) {
            val s = shoot(shortExp, iso).first
            means += s.green
            rowMod += s.rowModulation
        }
        rowMod /= patch.repeats
        val mean = means.average()
        val sd = Math.sqrt(means.map { (it - mean) * (it - mean) }.average())
        val cv = if (mean > 0) sd / mean else 0.0
        // Flicker index: combine frame-to-frame coefficient of variation with row banding.
        val flicker = max(cv, rowMod)
        val norm = normalise(ranged, rangedExp, iso)
        val xyz = camToXyz * norm
        return Measurement(
            patch.id, patch.group, patch.zone, intArrayOf(patch.r, patch.g, patch.b),
            norm, xyz, ColorMath.chromaticity(xyz), shortExp, iso, false, false, 0.0, rowMod, flicker, ranged.isRaw, patch.repeats,
            nitsFrom(norm[1]), aperture, calibration,
        )
    }

    /** Single reading for the live meter. */
    suspend fun readLive(): Measurement {
        val (stats, exp) = rangeExposure(lastExposure)
        lastExposure = exp
        val norm = normalise(stats, exp, baseIso)
        val xyz = camToXyz * norm
        val n = resultAperture(lastResult) ?: aperture
        return Measurement(
            "live", "live", 0, intArrayOf(-1, -1, -1), norm, xyz, ColorMath.chromaticity(xyz), exp, baseIso,
            stats.maxFraction > 0.95, stats.mean[1] <= 0.0, 0.0, stats.rowModulation, null, stats.isRaw, 1,
            nitsFrom(norm[1], n), n, calibration,
        )
    }
}
