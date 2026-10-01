package com.qkt.connector.gateway

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory

/**
 * The event half of a VGP v1 gateway: the `GET /v1/stream` WebSocket at [baseUrl], authenticated by
 * [apiKey]. New events reach [onEvent] in sequence order; an event whose `seq` was already processed
 * is dropped. A `reset` event, a change of `stream`, or a skipped `seq` (events lost) calls
 * [onReset] with the reason before anything newer is delivered, so the caller resynchronizes from REST.
 * A dropped socket reconnects with `since=<last seq>` after a backoff that starts at
 * [initialBackoffMs] and doubles to [maxBackoffMs]; [onConnection] hears every drop and reconnect.
 */
class GatewayStream(
    private val baseUrl: String,
    private val apiKey: String,
    private val onEvent: (WireEvent) -> Unit,
    private val onReset: (String) -> Unit,
    private val onConnection: (Boolean, String) -> Unit,
    private val initialBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 30_000,
) {
    private val log = LoggerFactory.getLogger(GatewayStream::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val reconnects: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "gateway-stream-reconnect").apply { isDaemon = true }
        }
    private val lock = Any()
    private var streamId: String? = null
    private var lastSeq: Long? = null
    private var backoffMs = initialBackoffMs
    private var socket: WebSocket? = null

    @Volatile private var stopped = false

    /** Takes [stream] at [seq] as already processed, so [start] replays everything after it. */
    fun anchor(
        stream: String,
        seq: Long,
    ) = synchronized(lock) {
        streamId = stream
        lastSeq = seq
    }

    /** Opens the stream from the last processed event, or live only when none is known. */
    fun start() {
        val since = synchronized(lock) { lastSeq }
        val url = baseUrl + "/v1/stream" + (since?.let { "?since=$it" } ?: "")
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .build()
        socket = http.newWebSocket(request, Listener())
    }

    /** Drops the stream for good, at once: no reconnect follows. */
    fun stop() {
        stopped = true
        socket?.cancel()
        reconnects.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun receive(text: String) {
        val event = json.decodeFromString(WireEvent.serializer(), text)
        synchronized(lock) {
            val reason =
                when {
                    event.type == "reset" -> "reset"
                    streamId != null && event.stream != streamId -> "stream $streamId -> ${event.stream}"
                    lastSeq != null && event.stream == streamId && event.seq <= lastSeq!! -> return
                    lastSeq != null && event.seq > lastSeq!! + 1 -> "seq $lastSeq -> ${event.seq}"
                    else -> null
                }
            streamId = event.stream
            lastSeq = event.seq
            if (reason != null) onReset(reason)
            if (event.type != "reset") onEvent(event)
        }
    }

    private fun reconnect(reason: String) {
        if (stopped) return
        onConnection(false, reason)
        val delay = synchronized(lock) { backoffMs.also { backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs) } }
        reconnects.schedule({ if (!stopped) start() }, delay, TimeUnit.MILLISECONDS)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            synchronized(lock) { backoffMs = initialBackoffMs }
            onConnection(true, "stream open")
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            // One malformed or undefined message is reported and skipped, never guessed at.
            runCatching { receive(text) }.onFailure { log.error("gateway stream message refused: {}", it.message) }
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) = reconnect("closed $code $reason")

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) = reconnect("failed: ${t.message}")
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
