package com.screenmeter.app.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the companion relay. Both the phone ("meter") and the browser ("display")
 * join a room identified by a short code; the relay forwards JSON messages between them.
 */
class RelayClient(
    private val onMessage: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null

    var connected: Boolean = false
        private set

    fun connect(baseUrl: String, code: String) {
        disconnect()
        val ws = baseUrl.trim().trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val url = "$ws/ws?code=${code.uppercase()}&role=meter"
        onState("connecting")
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                onState("connected")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { onMessage(JSONObject(text)) } catch (_: Exception) { }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false; onState("closed")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false; onState("error: ${t.message ?: t.javaClass.simpleName}")
            }
        })
    }

    fun send(obj: JSONObject): Boolean = socket?.send(obj.toString()) ?: false

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        connected = false
    }
}
