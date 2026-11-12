package com.screenmeter.app.measure

import com.screenmeter.app.color.ColorMath
import kotlin.math.ln

/** Small on-device summary; the companion page does the full analysis and charts. */
data class Summary(
    val whiteNits: Double?,
    val blackNits: Double?,
    val gamma: Double?,
    val contrast: Double?,
    val contrastIsLowerBound: Boolean,
    val whiteCct: Double?,
    val whiteX: Double?, val whiteY: Double?,
    val maxGreyDeltaUv: Double?,
    val flickerIndex: Double?,
    val uniformityMinPct: Double?,
)

object Summarizer {
    fun summarize(ms: List<Measurement>): Summary {
        val white = ms.firstOrNull { it.patchId == "white" }
        val black = ms.firstOrNull { it.patchId == "black" }
        val wy = white?.y ?: 0.0
        val greys = ms.filter { it.group == "grey" && !it.saturated && !it.atNoiseFloor && wy > 0 }
        // Least-squares gamma fit through the origin in log-log space.
        var num = 0.0; var den = 0.0
        greys.forEach { m ->
            val lx = ln(m.rgbIn[0] / 255.0); val ly = ln(m.y / wy)
            if (m.y > 0) { num += lx * ly; den += lx * lx }
        }
        val gamma = if (den > 0) num / den else null
        val contrast = if (white != null && black != null && black.y > 0) wy / black.y else null
        val cct = white?.let { ColorMath.cct(it.chroma.x, it.chroma.y) }
        val maxDuv = white?.let { w -> greys.filter { it.rgbIn[0] >= 32 }.maxOfOrNull { ColorMath.deltaUv(it.chroma, w.chroma) } }
        val flick = ms.firstOrNull { it.group == "flicker" }?.flickerIndex
        val zones = ms.filter { it.group == "uniformity" }
        val centre = zones.firstOrNull { it.zone == 5 }?.y
        val uniMin = if (centre != null && centre > 0) zones.minOf { it.y } / centre * 100.0 else null
        return Summary(white?.nits, black?.nits, gamma, contrast, black?.atNoiseFloor == true, cct, white?.chroma?.x, white?.chroma?.y, maxDuv, flick, uniMin)
    }
}
