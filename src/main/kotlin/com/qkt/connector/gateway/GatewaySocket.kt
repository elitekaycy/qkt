package com.qkt.connector.gateway

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory

/**
 * One WebSocket to a VGP v1 gateway, authenticated by [apiKey], that comes back by itself: each
 * connect asks [url] where to go (so a reconnect can resume from what was handled), each text message
 * goes to [onText], and a dropped socket reconnects after a backoff that starts at [initialBackoffMs]
 * and doubles to [maxBackoffMs]. [onConnection] hears every open and every drop. A message [onText]
 * throws on is logged and skipped, never guessed at. [name] labels the logs and the reconnect thread.
 */
internal class GatewaySocket(
    private val name: String,
    private val apiKey: String,
    private val url: () -> String,
    private val onText: (String) -> Unit,
    private val onConnection: (Boolean, String) -> Unit,
    private val initialBackoffMs: Long,
    private val maxBackoffMs: Long,
) {
    private val log = LoggerFactory.getLogger(GatewaySocket::class.java)
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val reconnects: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "gateway-$name-reconnect").apply { isDaemon = true }
        }
    private val lock = Any()
    private var backoffMs = initialBackoffMs

    @Volatile private var socket: WebSocket? = null

    @Volatile private var stopped = false

    /** Connects. */
    fun start() {
        val request =
            Request
                .Builder()
                .url(url())
                .header("Authorization", "Bearer $apiKey")
                .build()
        socket = http.newWebSocket(request, Listener())
    }

    /** Drops the socket for good, at once: no reconnect follows. */
    fun stop() {
        stopped = true
        socket?.cancel()
        reconnects.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
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
            onConnection(true, "$name open")
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            runCatching { onText(text) }.onFailure { log.error("gateway {} message refused: {}", name, it.message) }
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
