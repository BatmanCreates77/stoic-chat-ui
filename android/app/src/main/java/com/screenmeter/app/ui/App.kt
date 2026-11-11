package com.screenmeter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenmeter.app.AppState
import com.screenmeter.app.Screen
import com.screenmeter.app.color.ColorMath
import com.screenmeter.app.measure.Measurement
import java.util.Locale

private val Scheme = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    onPrimary = Color(0xFF00201C),
    secondary = Color(0xFFB8C4D0),
    background = Color(0xFF0B0E11),
    surface = Color(0xFF14191F),
    onBackground = Color(0xFFE8EDF2),
    onSurface = Color(0xFFE8EDF2),
    error = Color(0xFFFF7A7A),
)

@Composable
fun App(state: AppState, hasPermission: Boolean, requestPermission: () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        Box(Modifier.fillMaxSize().background(Scheme.background)) {
            when (state.screen) {
                Screen.Home -> HomeScreen(state, hasPermission, requestPermission)
                Screen.Run -> RunScreen(state)
                Screen.Live -> LiveScreen(state)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: AppState, hasPermission: Boolean, requestPermission: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("ScreenMeter") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasPermission) {
                Card { Column(Modifier.padding(16.dp)) {
                    Text("Camera permission is required to measure the screen.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = requestPermission) { Text("Grant camera access") }
                } }
            }
            CapabilityCard(state)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Companion page", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = state.relayUrl, onValueChange = { state.relayUrl = it }, label = { Text("Relay URL (http://host:port)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state.code, onValueChange = { state.code = it.uppercase().take(6) }, label = { Text("Session code from the page") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.connect() }) { Text("Connect") }
                        OutlinedButton(onClick = { state.disconnect() }) { Text("Disconnect") }
                    }
                    Text("Relay: ${state.relayState}", style = MaterialTheme.typography.bodySmall)
                    Text(if (state.displayPresent) "Display connected ${state.displayInfo}" else "Display not connected", color = if (state.displayPresent) Scheme.primary else Scheme.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Measure", style = MaterialTheme.typography.titleMedium)
                    Text("Press the main camera flat against the centre of the monitor. Keep the room dark. The page will cycle through grey, colour and flicker patterns.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.includeUniformity, onCheckedChange = { state.includeUniformity = it })
                        Text("Include 9-zone uniformity (you move the phone)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = hasPermission && state.displayPresent && !state.running, onClick = { state.startRun(false) }) { Text("Full test") }
                        OutlinedButton(enabled = hasPermission && state.displayPresent && !state.running, onClick = { state.startRun(true) }) { Text("Quick test") }
                    }
                    OutlinedButton(enabled = hasPermission, onClick = { state.toggleLive() }) { Text("Live meter (any app or site)") }
                    if (state.statusText.isNotBlank()) Text(state.statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(state: AppState) {
    val c = state.capabilities
    Card(colors = CardDefaults.cardColors(containerColor = Scheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Camera", style = MaterialTheme.typography.titleMedium)
            if (state.cameraError != null) Text("Error: ${state.cameraError}", color = Scheme.error)
            if (c == null) { Text("Not probed yet", style = MaterialTheme.typography.bodySmall); return@Column }
            KV("Camera id", c.cameraId)
            KV("RAW capture", if (c.supportsRaw) "yes (${c.cfaName}, white ${c.whiteLevel})" else "no, YUV fallback (approximate)")
            KV("Manual exposure", if (c.supportsManualSensor) "yes" else "no (results unreliable)")
            KV("Exposure range", c.exposureRangeNs?.let { "${fmtNs(it.lower)} to ${fmtNs(it.upper)}" } ?: "n/a")
            KV("ISO range", c.isoRange?.let { "${it.lower} to ${it.upper}" } ?: "n/a")
            KV("Colour matrices", if (c.hasColorMatrices) "from camera (XYZ)" else "absent, sRGB assumed")
            KV("Output", "${c.outputSize.width}x${c.outputSize.height} ${c.formatName}")
            val grade = when {
                c.supportsRaw && c.supportsManualSensor && c.hasColorMatrices -> "Good: relative luminance, gamma and chromaticity are usable."
                c.supportsManualSensor -> "Limited: gamma and contrast only; colour is rough."
                else -> "Poor: this phone cannot lock exposure. Readings are indicative only."
            }
            Spacer(Modifier.height(4.dp))
            Text(grade, color = Scheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row { Text(k, Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall, color = Scheme.secondary); Text(v, style = MaterialTheme.typography.bodySmall) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunScreen(state: AppState) {
    BackHandler { if (!state.running) state.screen = Screen.Home }
    Scaffold(topBar = { TopAppBar(title = { Text("Measuring") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val (i, n) = state.progress
            LinearProgressIndicator(progress = { if (n == 0) 0f else i.toFloat() / n }, modifier = Modifier.fillMaxWidth())
            Text("${state.currentLabel}  ($i / $n)", style = MaterialTheme.typography.titleMedium)
            Text(state.statusText, style = MaterialTheme.typography.bodySmall)
            state.waitingForUser?.let { msg ->
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(msg)
                    Button(onClick = { state.userContinue() }) { Text("Continue") }
                } }
            }
            state.summary?.let { s ->
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Summary", style = MaterialTheme.typography.titleMedium)
                    KV("Gamma", s.gamma?.let { fmt(it, 2) } ?: "n/a")
                    KV("Contrast", s.contrast?.let { (if (s.contrastIsLowerBound) "> " else "") + "${fmt(it, 0)}:1" } ?: "n/a")
                    KV("White point", if (s.whiteX != null) "x ${fmt(s.whiteX, 4)}  y ${fmt(s.whiteY!!, 4)}  ~${fmt(s.whiteCct!!, 0)} K" else "n/a")
                    KV("Grey tracking", s.maxGreyDeltaUv?.let { "max du'v' ${fmt(it, 4)}" } ?: "n/a")
                    KV("Flicker index", s.flickerIndex?.let { fmt(it, 3) + if (it > 0.05) " (PWM likely)" else " (flicker-free)" } ?: "n/a")
                    KV("Uniformity", s.uniformityMinPct?.let { "darkest zone ${fmt(it, 0)}% of centre" } ?: "not run")
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) OutlinedButton(onClick = { state.cancelRun() }) { Text("Cancel") }
                else Button(onClick = { state.screen = Screen.Home }) { Text("Back") }
            }
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.measurements.reversed()) { m -> MeasurementRow(m) }
            }
        }
    }
}

@Composable
private fun MeasurementRow(m: Measurement) {
    Row(Modifier.fillMaxWidth().background(Scheme.surface, RoundedCornerShape(6.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).background(Color(m.rgbIn[0].coerceAtLeast(0), m.rgbIn[1].coerceAtLeast(0), m.rgbIn[2].coerceAtLeast(0)), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(m.patchId, Modifier.width(90.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("Y ${fmt(m.y, 3)}  x ${fmt(m.chroma.x, 3)} y ${fmt(m.chroma.y, 3)}  ${fmtNs(m.exposureNs)}" +
            (if (m.saturated) " CLIP" else "") + (if (m.atNoiseFloor) " noise" else "") + (m.flickerIndex?.let { " flicker ${fmt(it, 3)}" } ?: ""),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveScreen(state: AppState) {
    BackHandler { if (state.liveRunning) state.toggleLive(); state.screen = Screen.Home }
    Scaffold(topBar = { TopAppBar(title = { Text("Live meter") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hold the camera against any screen content: a website, a game, a video. Values are relative to the phone sensor, not nits. Tap \"Set as reference\" on full white to read everything else as a percentage.", style = MaterialTheme.typography.bodySmall)
            val m = state.live
            val ref = state.liveReference
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m == null) Text("Waiting for first reading") else {
                    val pct = if (ref != null && ref.y > 0) m.y / ref.y * 100 else null
                    Text(if (pct != null) "${fmt(pct, 1)} % of reference" else "Y ${fmt(m.y, 4)} (relative)", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    KV("Chromaticity", "x ${fmt(m.chroma.x, 4)}  y ${fmt(m.chroma.y, 4)}")
                    KV("CCT", "~${fmt(ColorMath.cct(m.chroma.x, m.chroma.y), 0)} K")
                    if (ref != null) KV("du'v' vs ref", fmt(ColorMath.deltaUv(m.chroma, ref.chroma), 4))
                    KV("Exposure", "${fmtNs(m.exposureNs)} @ ISO ${m.iso}")
                    KV("Row banding", fmt(m.rowModulation, 3))
                    if (m.saturated) Text("Clipping: too bright for the shortest exposure", color = Scheme.error)
                    if (!m.isRaw) Text("YUV fallback: tone-mapped by the phone, approximate", color = Scheme.error)
                }
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { state.toggleLive() }) { Text(if (state.liveRunning) "Stop" else "Start") }
                OutlinedButton(enabled = m != null, onClick = { state.setLiveReference() }) { Text("Set as reference") }
                OutlinedButton(onClick = { if (state.liveRunning) state.toggleLive(); state.screen = Screen.Home }) { Text("Back") }
            }
            if (state.statusText.isNotBlank()) Text(state.statusText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun fmt(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)
private fun fmtNs(ns: Long): String = when {
    ns >= 1_000_000_000L -> fmt(ns / 1e9, 2) + " s"
    ns >= 1_000_000L -> fmt(ns / 1e6, 1) + " ms"
    else -> fmt(ns / 1e3, 0) + " us"
}
