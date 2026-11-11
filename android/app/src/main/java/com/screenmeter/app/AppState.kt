package com.screenmeter.app

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.screenmeter.app.camera.CameraCapabilities
import com.screenmeter.app.camera.CameraController
import com.screenmeter.app.measure.Measurement
import com.screenmeter.app.measure.MeasurementEngine
import com.screenmeter.app.measure.Patch
import com.screenmeter.app.measure.Summarizer
import com.screenmeter.app.measure.Summary
import com.screenmeter.app.measure.TestSuite
import com.screenmeter.app.sync.RelayClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class Screen { Home, Run, Live }

/** Single source of truth for the UI. Lives for the whole activity. */
class AppState(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("screenmeter", Context.MODE_PRIVATE)
    val camera = CameraController(context.applicationContext)
    private var engine: MeasurementEngine? = null

    var screen by mutableStateOf(Screen.Home)
    var capabilities by mutableStateOf<CameraCapabilities?>(null)
    var cameraError by mutableStateOf<String?>(null)
    var relayUrl by mutableStateOf(prefs.getString("relayUrl", "http://192.168.1.10:8787") ?: "")
    var code by mutableStateOf(prefs.getString("code", "") ?: "")
    var relayState by mutableStateOf("disconnected")
    var displayPresent by mutableStateOf(false)
    var displayInfo by mutableStateOf("")
    var includeUniformity by mutableStateOf(false)

    // Run state
    var running by mutableStateOf(false)
    var progress by mutableStateOf(0 to 0)
    var currentLabel by mutableStateOf("")
    var statusText by mutableStateOf("")
    val measurements = mutableStateListOf<Measurement>()
    var summary by mutableStateOf<Summary?>(null)
    var waitingForUser by mutableStateOf<String?>(null)
    private var runJob: Job? = null
    private var shownWaiters = HashMap<String, CompletableDeferred<Unit>>()
    private var userContinue: CompletableDeferred<Unit>? = null

    // Live meter
    var liveRunning by mutableStateOf(false)
    var live by mutableStateOf<Measurement?>(null)
    var liveReference by mutableStateOf<Measurement?>(null)
    private var liveJob: Job? = null

    private val relay: RelayClient = RelayClient(onMessage = ::onRelayMessage, onState = { s ->
        relayState = s
        if (s != "connected") displayPresent = false
        if (s == "connected") relay.send(JSONObject().put("type", "hello").put("role", "meter").put("device", "${Build.MANUFACTURER} ${Build.MODEL}"))
    })

    fun probeCamera() {
        try {
            capabilities = camera.probe()
            cameraError = null
        } catch (e: Exception) {
            cameraError = e.message ?: e.toString()
        }
    }

    fun connect() {
        prefs.edit().putString("relayUrl", relayUrl).putString("code", code).apply()
        if (code.isBlank()) { relayState = "enter the code shown on the companion page"; return }
        relay.connect(relayUrl, code)
    }

    fun disconnect() { relay.disconnect(); displayPresent = false }

    private fun onRelayMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "hello" -> if (msg.optString("role") == "display") {
                displayPresent = true
                val s = msg.optJSONObject("screen")
                displayInfo = if (s != null) "${s.optInt("w")}x${s.optInt("h")} @${s.optDouble("dpr", 1.0)}x" else ""
                sendCameraInfo()
            }
            "peer_left" -> if (msg.optString("role") == "display") displayPresent = false
            "shown" -> shownWaiters.remove(msg.optString("id"))?.complete(Unit)
            "start" -> if (!running) startRun(msg.optString("suite", "full") == "quick")
            "stop" -> cancelRun()
        }
    }

    private fun sendCameraInfo() {
        val c = capabilities ?: return
        relay.send(JSONObject().put("type", "camera").put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("raw", c.supportsRaw).put("manual", c.supportsManualSensor).put("cfa", c.cfaName)
            .put("format", c.formatName).put("colorMatrix", c.hasColorMatrices)
            .put("exposureMin", c.exposureRangeNs?.lower ?: -1).put("exposureMax", c.exposureRangeNs?.upper ?: -1)
            .put("isoMin", c.isoRange?.lower ?: -1).put("isoMax", c.isoRange?.upper ?: -1))
    }

    private suspend fun ensureCamera(): MeasurementEngine {
        if (!camera.isOpen) withContext(Dispatchers.Main) { camera.open() }
        return engine ?: MeasurementEngine(camera).also { engine = it }
    }

    fun startRun(quick: Boolean) {
        if (running) return
        val patches = if (quick) TestSuite.quick() else TestSuite.full(includeUniformity)
        measurements.clear(); summary = null
        running = true; screen = Screen.Run; statusText = "Opening camera"
        progress = 0 to patches.size
        runJob = scope.launch {
            try {
                val eng = ensureCamera()
                relay.send(JSONObject().put("type", "run_start").put("total", patches.size).put("quick", quick))
                for ((i, p) in patches.withIndex()) {
                    if (!isActive) break
                    progress = i to patches.size
                    currentLabel = p.label
                    if (p.group == "uniformity") {
                        showPatch(p, i, patches.size)
                        waitingForUser = "Move the phone to zone ${p.zone} (${zoneName(p.zone)}), hold it flat against the screen, then tap Continue."
                        awaitUser()
                    } else {
                        statusText = "Waiting for display"
                        showPatch(p, i, patches.size)
                    }
                    statusText = "Measuring ${p.label}"
                    val m = withContext(Dispatchers.Default) { eng.measure(p) }
                    measurements += m
                    relay.send(m.toJson())
                }
                progress = patches.size to patches.size
                summary = Summarizer.summarize(measurements)
                relay.send(JSONObject().put("type", "run_done").put("count", measurements.size))
                statusText = "Done. Full report is on the companion page."
            } catch (e: Exception) {
                statusText = "Stopped: ${e.message ?: e.toString()}"
                relay.send(JSONObject().put("type", "status").put("text", statusText))
            } finally {
                running = false; waitingForUser = null
                relay.send(JSONObject().put("type", "show").put("id", "idle").put("rgb", JSONArray(listOf(24, 24, 24))).put("zone", 0).put("label", "Idle"))
            }
        }
    }

    private fun zoneName(z: Int): String = listOf("top-left", "top-centre", "top-right", "middle-left", "centre", "middle-right", "bottom-left", "bottom-centre", "bottom-right").getOrElse(z - 1) { "" }

    private suspend fun showPatch(p: Patch, index: Int, total: Int) {
        val waiter = CompletableDeferred<Unit>()
        shownWaiters[p.id] = waiter
        val sent = relay.send(JSONObject().put("type", "show").put("id", p.id)
            .put("rgb", JSONArray(listOf(p.r, p.g, p.b))).put("zone", p.zone).put("label", p.label)
            .put("index", index).put("total", total))
        if (!sent || !displayPresent) {
            shownWaiters.remove(p.id)
            throw IllegalStateException("Companion display is not connected")
        }
        val ok = withTimeoutOrNull(6_000) { waiter.await() }
        if (ok == null) statusText = "No ack from display, measuring anyway"
        delay(400) // panel response + browser paint
    }

    private suspend fun awaitUser() {
        val d = CompletableDeferred<Unit>()
        userContinue = d
        d.await()
        waitingForUser = null
        delay(300)
    }

    fun userContinue() { userContinue?.complete(Unit) }

    fun cancelRun() { runJob?.cancel(); running = false; waitingForUser = null; statusText = "Cancelled" }

    fun toggleLive() {
        if (liveRunning) { liveJob?.cancel(); liveRunning = false; return }
        liveRunning = true; screen = Screen.Live
        liveJob = scope.launch {
            try {
                val eng = ensureCamera()
                while (isActive) {
                    val m = withContext(Dispatchers.Default) { eng.readLive() }
                    live = m
                    if (relay.connected) relay.send(m.toJson().put("type", "live"))
                    delay(150)
                }
            } catch (e: Exception) {
                statusText = "Live meter stopped: ${e.message}"
                liveRunning = false
            }
        }
    }

    fun setLiveReference() { liveReference = live }

    fun release() { runJob?.cancel(); liveJob?.cancel(); camera.close(); relay.disconnect() }
}
