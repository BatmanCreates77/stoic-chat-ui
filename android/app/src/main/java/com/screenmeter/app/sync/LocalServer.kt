package com.screenmeter.app.sync

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The phone hosts the companion page: a small HTTP server for the files in assets/ (the repo's
 * web/ folder) and a WebSocket endpoint at /ws that the page uses. Messages from the page go to
 * [onMessage]; [send] broadcasts to every open page. This replaces the Node relay for the normal
 * case where phone and computer share a Wi-Fi network.
 */
class LocalServer(
    /** Opens a bundled file by relative path (assets/ on Android), or throws IOException. */
    private val openAsset: (String) -> InputStream,
    port: Int,
    private val onMessage: (JSONObject) -> Unit,
    private val onDisplays: (Int) -> Unit,
) : NanoWSD(port) {

    private val sockets = CopyOnWriteArraySet<DisplaySocket>()
    private var pinger: Timer? = null

    val displayCount: Int get() = sockets.size

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
        pinger = Timer("ScreenMeterPing", true).apply {
            schedule(object : TimerTask() {
                override fun run() { for (s in sockets) try { s.ping(ByteArray(0)) } catch (_: IOException) { s.safeClose() } }
            }, 2000, 2000)
        }
    }

    fun stopServer() {
        pinger?.cancel(); pinger = null
        for (s in sockets) s.safeClose()
        sockets.clear()
        stop()
    }

    fun send(obj: JSONObject): Boolean {
        val text = obj.toString()
        var n = 0
        for (s in sockets) { try { s.send(text); n++ } catch (_: IOException) { s.safeClose() } }
        return n > 0
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = DisplaySocket(handshake)

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/api/health") {
            return json("""{"ok":true,"mode":"phone","displays":${sockets.size}}""")
        }
        val path = (if (uri == "/" || uri.isEmpty()) "index.html" else uri.trimStart('/')).replace("..", "")
        return try {
            val stream = openAsset(path)
            val r = newChunkedResponse(Response.Status.OK, mimeFor(path), stream)
            r.addHeader("Cache-Control", "no-cache")
            r
        } catch (_: IOException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    }

    private fun json(body: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    inner class DisplaySocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() { sockets.add(this); onDisplays(sockets.size) }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            if (sockets.remove(this)) { onDisplays(sockets.size); onMessage(JSONObject().put("type", "peer_left").put("role", "display")) }
        }
        override fun onMessage(message: WebSocketFrame) {
            try { onMessage(JSONObject(message.textPayload)) } catch (_: Exception) { }
        }
        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) { safeClose() }
        fun safeClose() { try { close(WebSocketFrame.CloseCode.NormalClosure, "bye", false) } catch (_: Exception) { } ; sockets.remove(this) }
    }

    companion object {
        const val DEFAULT_PORT = 8787

        /** Site-local IPv4 addresses of this phone, Wi-Fi first. */
        fun localAddresses(): List<String> {
            val out = mutableListOf<String>()
            try {
                for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (addr in Collections.list(ni.inetAddresses)) {
                        if (addr is Inet4Address && addr.isSiteLocalAddress) out += (if (ni.name.startsWith("wlan")) "" else "~") + addr.hostAddress
                    }
                }
            } catch (_: Exception) { }
            return out.sortedBy { it.startsWith("~") }.map { it.trimStart('~') }
        }
    }
}
