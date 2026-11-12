package com.screenmeter.app

import com.screenmeter.app.sync.LocalServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Drives the phone-hosted server with real HTTP and WebSocket clients (OkHttp, as a browser would). */
class LocalServerTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val files = mapOf("index.html" to "<title>ScreenMeter</title>", "js/app.js" to "console.log(1)")

    @Test
    fun servesAssetsHealthAndRelaysWebSocketMessagesBothWays() {
        val port = freePort()
        val received = LinkedBlockingQueue<JSONObject>()
        val server = LocalServer({ p -> files[p]?.let { ByteArrayInputStream(it.toByteArray()) } ?: throw FileNotFoundException(p) }, port, { received.add(it) }, { })
        server.startServer()
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        try {
            fun get(path: String): Response = client.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute()
            get("/").use { assertEquals(200, it.code); assertTrue(it.body!!.string().contains("ScreenMeter")) }
            get("/js/app.js").use { assertEquals(200, it.code); assertTrue(it.header("Content-Type")!!.startsWith("text/javascript")) }
            get("/api/health").use { assertTrue(it.body!!.string().contains("\"mode\":\"phone\"")) }
            get("/nope.js").use { assertEquals(404, it.code) }

            val fromServer = LinkedBlockingQueue<String>()
            val opened = LinkedBlockingQueue<Boolean>()
            val closed = LinkedBlockingQueue<Boolean>()
            val ws = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/ws?code=LOCAL&role=display").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.add(true) }
                override fun onMessage(webSocket: WebSocket, text: String) { fromServer.add(text) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.add(true) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.add(false) }
            })
            assertNotNull("websocket did not open", opened.poll(5, TimeUnit.SECONDS))
            // Browser -> phone
            assertTrue(ws.send("""{"type":"hello","role":"display","screen":{"w":1512,"h":982,"dpr":2}}"""))
            val hello = received.poll(5, TimeUnit.SECONDS)
            assertNotNull("server did not receive hello", hello)
            assertEquals("hello", hello!!.getString("type"))
            assertEquals(1512, hello.getJSONObject("screen").getInt("w"))
            // Phone -> browser
            assertTrue(server.send(JSONObject().put("type", "show").put("id", "white")))
            val shown = fromServer.poll(5, TimeUnit.SECONDS)
            assertNotNull("browser did not receive show", shown)
            assertEquals("white", JSONObject(shown!!).getString("id"))
            assertEquals(1, server.displayCount)
            // Pings from the server must not break the client for a few seconds
            Thread.sleep(2500)
            assertTrue(server.send(JSONObject().put("type", "status").put("text", "still here")))
            assertNotNull(fromServer.poll(5, TimeUnit.SECONDS))
            // Close from the browser side
            ws.close(1000, "bye")
            val left = received.poll(5, TimeUnit.SECONDS)
            assertNotNull("server did not notice the close", left)
            assertEquals("peer_left", left!!.getString("type"))
            assertEquals(0, server.displayCount)
        } finally {
            server.stopServer()
            client.dispatcher.executorService.shutdown()
        }
    }
}
