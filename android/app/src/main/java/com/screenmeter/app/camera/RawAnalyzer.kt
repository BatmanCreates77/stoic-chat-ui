package com.screenmeter.app.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reduces a RAW_SENSOR (or fallback YUV) frame to channel statistics inside a centred
 * region of interest. The ROI is deliberately small because a phone lens pressed against a
 * screen vignettes heavily toward the frame edges.
 */
object RawAnalyzer {

    fun analyze(
        image: Image,
        result: TotalCaptureResult,
        chars: CameraCharacteristics,
        roiFraction: Double = 0.25,
    ): RawStats = when (image.format) {
        ImageFormat.RAW_SENSOR -> analyzeRaw(image, result, chars, roiFraction)
        ImageFormat.YUV_420_888 -> analyzeYuv(image, roiFraction)
        else -> throw IllegalArgumentException("Unsupported image format ${image.format}")
    }

    /** Channel index (0=R, 1=G, 2=B) for the pixel at (row, col) in the given CFA layout. */
    private fun channelFor(cfa: Int, row: Int, col: Int): Int {
        val r = row and 1
        val c = col and 1
        return when (cfa) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
                if (r == 0) (if (c == 0) 0 else 1) else (if (c == 0) 1 else 2)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
                if (r == 0) (if (c == 0) 1 else 0) else (if (c == 0) 2 else 1)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
                if (r == 0) (if (c == 0) 1 else 2) else (if (c == 0) 0 else 1)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR ->
                if (r == 0) (if (c == 0) 2 else 1) else (if (c == 0) 1 else 0)
            else -> 1 // monochrome or RGB sensors: treat everything as "green"
        }
    }

    private fun analyzeRaw(
        image: Image,
        result: TotalCaptureResult,
        chars: CameraCharacteristics,
        roiFraction: Double,
    ): RawStats {
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1

        // Black level per 2x2 CFA position, dynamic if the HAL reports it for this frame.
        val black = DoubleArray(4)
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (pattern != null) {
            for (row in 0..1) for (col in 0..1) black[row * 2 + col] = pattern.getOffsetForIndex(col, row).toDouble()
        }
        result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { dyn ->
            if (dyn.size == 4) for (i in 0..3) black[i] = dyn[i].toDouble()
        }
        val white = (result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()

        val roiW = max(16, (w * roiFraction).toInt()) and 1.inv()
        val roiH = max(16, (h * roiFraction).toInt()) and 1.inv()
        val x0 = ((w - roiW) / 2) and 1.inv()
        val y0 = ((h - roiH) / 2) and 1.inv()

        val sum = DoubleArray(3)
        val sumSq = DoubleArray(3)
        val n = IntArray(3)
        var maxV = 0.0

        for (row in y0 until y0 + roiH) {
            val rowBase = row * rowStride
            for (col in x0 until x0 + roiW) {
                val raw = (buf.getShort(rowBase + col * pixelStride).toInt() and 0xFFFF).toDouble()
                val b = black[(row and 1) * 2 + (col and 1)]
                val v = (raw - b) / (white - b)
                val ch = channelFor(cfa, row, col)
                sum[ch] += v
                sumSq[ch] += v * v
                n[ch]++
                if (v > maxV) maxV = v
            }
        }

        val mean = DoubleArray(3)
        val std = DoubleArray(3)
        for (c in 0..2) {
            if (n[c] > 0) {
                mean[c] = sum[c] / n[c]
                std[c] = sqrt(max(0.0, sumSq[c] / n[c] - mean[c] * mean[c]))
            }
        }
        if (cfa !in 0..3) { // mono: copy the single channel to all three
            mean[0] = mean[1]; mean[2] = mean[1]; std[0] = std[1]; std[2] = std[1]
        }

        // Row modulation across the full sensor height (centre column band), coarse subsampling.
        val rowMod = rowModulation(h) { row ->
            var s = 0.0
            var k = 0
            val rowBase = row * rowStride
            var col = x0
            while (col < x0 + roiW) {
                val raw = (buf.getShort(rowBase + col * pixelStride).toInt() and 0xFFFF).toDouble()
                s += raw - black[(row and 1) * 2 + (col and 1)]
                k++
                col += 8
            }
            if (k == 0) 0.0 else s / k
        }

        return RawStats(image.format, w, h, mean, std, n, maxV, rowMod, isRaw = true)
    }

    /**
     * Fallback for devices without RAW: luma plane only, device tone curve already applied.
     * Values are linearised with an sRGB curve as a rough approximation and flagged as non-RAW.
     */
    private fun analyzeYuv(image: Image, roiFraction: Double): RawStats {
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val roiW = max(16, (w * roiFraction).toInt())
        val roiH = max(16, (h * roiFraction).toInt())
        val x0 = (w - roiW) / 2
        val y0 = (h - roiH) / 2
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var maxV = 0.0
        for (row in y0 until y0 + roiH) {
            val base = row * rowStride
            for (col in x0 until x0 + roiW) {
                val enc = (buf.get(base + col * pixelStride).toInt() and 0xFF) / 255.0
                val v = com.screenmeter.app.color.ColorMath.srgbEncodedToLinear(enc)
                sum += v; sumSq += v * v; n++
                if (v > maxV) maxV = v
            }
        }
        val mean = if (n > 0) sum / n else 0.0
        val std = if (n > 0) sqrt(max(0.0, sumSq / n - mean * mean)) else 0.0
        val rowMod = rowModulation(h) { row ->
            var s = 0.0; var k = 0
            val base = row * rowStride
            var col = x0
            while (col < x0 + roiW) { s += (buf.get(base + col * pixelStride).toInt() and 0xFF); k++; col += 8 }
            if (k == 0) 0.0 else s / k
        }
        return RawStats(
            image.format, w, h,
            doubleArrayOf(mean, mean, mean), doubleArrayOf(std, std, std), intArrayOf(n, n, n),
            maxV, rowMod, isRaw = false,
        )
    }

    private inline fun rowModulation(height: Int, rowMean: (Int) -> Double): Double {
        val step = max(1, height / 240)
        var s = 0.0; var sq = 0.0; var k = 0
        var row = 0
        while (row < height) {
            val m = rowMean(row)
            s += m; sq += m * m; k++
            row += step
        }
        if (k < 2) return 0.0
        val mean = s / k
        if (mean <= 1e-9) return 0.0
        val std = sqrt(max(0.0, sq / k - mean * mean))
        return std / mean
    }
}
