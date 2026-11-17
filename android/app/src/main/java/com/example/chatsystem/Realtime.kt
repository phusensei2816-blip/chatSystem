package com.example.chatsystem

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject

/**
 * WebSocket + heartbeat + tự kết nối lại (backoff 1s → 15s).
 * Interval/timeout do server cấp qua tin {"type":"config"}.
 * frozen = true: ngừng gửi heartbeat nhưng giữ socket, để demo "mất mạng ngầm" -> server đánh offline sau timeout.
 */
class Realtime(
    private val scope: CoroutineScope,
    private val onEvent: (JSONObject) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private var job: Job? = null
    @Volatile private var interval = 15
    @Volatile private var timeout = 45
    @Volatile private var lastAck = 0L
    @Volatile var frozen = false

    fun start(url: String) {
        stop()
        job = scope.launch {
            var backoff = 1000L
            while (isActive) {
                val closed = CompletableDeferred<Unit>()
                lastAck = System.currentTimeMillis()
                ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { backoff = 1000L; onState(true) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val j = JSONObject(text)
                        when (j.optString("type")) {
                            "config" -> { interval = j.getInt("interval"); timeout = j.getInt("timeout") }
                            "heartbeat_ack" -> lastAck = System.currentTimeMillis()
                        }
                        onEvent(j)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                })
                val beat = launch {
                    while (isActive) {
                        delay(interval * 1000L)
                        if (frozen) { lastAck = System.currentTimeMillis(); continue }
                        ws?.send("""{"type":"heartbeat"}""")
                        if (System.currentTimeMillis() - lastAck > timeout * 1000L) { ws?.cancel(); closed.complete(Unit) }
                    }
                }
                closed.await()
                beat.cancel()
                onState(false)
                delay(backoff)
                backoff = minOf(backoff * 2, 15000L)
            }
        }
    }

    fun send(json: String): Boolean = ws?.send(json) == true

    fun stop() {
        job?.cancel(); job = null
        ws?.close(1000, null); ws = null
        onState(false)
    }
}
