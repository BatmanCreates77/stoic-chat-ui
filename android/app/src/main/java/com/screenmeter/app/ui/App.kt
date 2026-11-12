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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
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
                    if (!state.useRemoteRelay) {
                        Text("The phone serves the page. On a computer on the same Wi-Fi, open:", style = MaterialTheme.typography.bodySmall)
                        if (state.serverAddresses.isEmpty()) Text(state.serverState, color = Scheme.error, style = MaterialTheme.typography.bodySmall)
                        state.serverAddresses.forEach { ip ->
                            Text("http://$ip:8787", fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Scheme.primary)
                        }
                        Text("If it does not load, the network may isolate devices: turn on the phone's hotspot, join it from the computer, and tap Refresh.", style = MaterialTheme.typography.bodySmall, color = Scheme.secondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { state.refreshAddresses() }) { Text("Refresh address") }
                        }
                    } else {
                        OutlinedTextField(value = state.relayUrl, onValueChange = { state.relayUrl = it }, label = { Text("Relay URL (http://host:port)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = state.code, onValueChange = { state.code = it.uppercase().take(6) }, label = { Text("Session code from the page") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { state.connect() }) { Text("Connect") }
                            OutlinedButton(onClick = { state.disconnect() }) { Text("Disconnect") }
                        }
                        Text("Relay: ${state.relayState}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (state.displayPresent) "Display connected ${state.displayInfo}" else "Waiting for a browser to open the page", color = if (state.displayPresent) Scheme.primary else Scheme.secondary, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.useRemoteRelay, onCheckedChange = { state.useRemoteRelay = it })
                        Text("Use a remote relay server instead (advanced)", style = MaterialTheme.typography.bodySmall)
                    }
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
                    Button(enabled = hasPermission, onClick = { state.toggleLive() }) { Text("Luminance meter (nits)") }
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
                    KV("White", s.whiteNits?.let { "${fmt(it, 0)} nits" } ?: "n/a")
                    KV("Black", s.blackNits?.let { "${fmt(it, 3)} nits" + if (s.contrastIsLowerBound) " (noise floor)" else "" } ?: "n/a")
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
        Text("${fmt(m.nits, if (m.nits < 10) 2 else 0)} nits  x ${fmt(m.chroma.x, 3)} y ${fmt(m.chroma.y, 3)}  ${fmtNs(m.exposureNs)}" +
            (if (m.saturated) " CLIP" else "") + (if (m.atNoiseFloor) " noise" else "") + (m.flickerIndex?.let { " flicker ${fmt(it, 3)}" } ?: ""),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveScreen(state: AppState) {
    BackHandler { if (state.liveRunning) state.toggleLive(); state.screen = Screen.Home }
    var knownNits by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text("Luminance meter") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Press the main camera flat against the screen area you want to measure: a white patch, a website, a game. Room lights off.", style = MaterialTheme.typography.bodySmall)
            val m = state.live
            val ref = state.liveReference
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m == null) Text("Waiting for first reading") else {
                    Text("${fmt(m.nits, if (m.nits < 10) 2 else 0)} nits", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Scheme.primary)
                    Text(if (state.calibration == 1.0) "Uncalibrated: expect about ±35 %. Calibrate once below." else "Calibrated ×${fmt(state.calibration, 3)}" + (if (state.calibrationNote.isNotBlank()) " (${state.calibrationNote})" else ""), style = MaterialTheme.typography.bodySmall, color = Scheme.secondary)
                    if (ref != null && ref.nits > 0) KV("vs reference", "${fmt(m.nits / ref.nits * 100, 1)} %  du'v' ${fmt(ColorMath.deltaUv(m.chroma, ref.chroma), 4)}")
                    KV("Chromaticity", "x ${fmt(m.chroma.x, 4)}  y ${fmt(m.chroma.y, 4)}  ~${fmt(ColorMath.cct(m.chroma.x, m.chroma.y), 0)} K")
                    KV("Exposure", "${fmtNs(m.exposureNs)}  ISO ${m.iso}  f/${fmt(m.aperture, 1)}")
                    KV("Signal", "${fmt(m.camRgb[1] * m.exposureNs / 1e9 * m.iso / 100 * 100, 1)} % of sensor full scale")
                    KV("Row banding", fmt(m.rowModulation, 3))
                    if (m.saturated) Text("Clipping: too bright even at the shortest exposure", color = Scheme.error)
                    if (!m.isRaw) Text("YUV fallback: tone-mapped by the phone, nits are rough", color = Scheme.error)
                }
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { state.toggleLive() }) { Text(if (state.liveRunning) "Stop" else "Start") }
                OutlinedButton(enabled = m != null, onClick = { state.setLiveReference() }) { Text("Set reference") }
                OutlinedButton(onClick = { if (state.liveRunning) state.toggleLive(); state.screen = Screen.Home }) { Text("Back") }
            }
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cross-check with the light sensor", style = MaterialTheme.typography.titleMedium)
                if (!state.hasLightSensor) Text("This phone reports no ambient light sensor.", style = MaterialTheme.typography.bodySmall)
                else {
                    val lux = state.lux
                    Text(if (lux == null) "No reading yet" else "${fmt(lux.toDouble(), 0)} lux  →  ${fmt(lux / Math.PI, 0)} nits", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Flip the phone and lay its top edge face-down on a large white area so the sensor (under the display, near the top) sees only the screen. A screen that fills the sensor's view gives lux = π × nits. The front camera must not be covered by your hand.", style = MaterialTheme.typography.bodySmall)
                }
            } }
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Calibrate once", style = MaterialTheme.typography.titleMedium)
                Text("Point the camera at a screen whose white luminance you know (a monitor you measured with a real meter, or a laptop at 100 % brightness with a published spec), wait for a steady reading, enter the value.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = knownNits, onValueChange = { knownNits = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Known nits of what the camera sees now") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = m != null && knownNits.toDoubleOrNull() != null, onClick = { state.calibrateTo(knownNits.toDouble(), "ref ${knownNits} nits") }) { Text("Apply") }
                    OutlinedButton(enabled = state.calibration != 1.0, onClick = { state.resetCalibration() }) { Text("Reset to first principles") }
                }
            } }
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
