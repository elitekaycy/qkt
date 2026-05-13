package com.qkt.marketdata.live.bybit

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.slf4j.LoggerFactory

/**
 * Production [BybitPublicWsLike] backed by an OkHttp WebSocket to Bybit's public stream
 * endpoints (`/v5/public/spot` or `/v5/public/linear`).
 *
 * No auto-reconnect at this layer — disconnects surface via [BybitPublicListener.onDisconnected];
 * a reconnect supervisor (future work) is the right place for retry policy.
 */
class BybitPublicWs(
    private val url: String,
    private val client: OkHttpClient = defaultClient(),
) : BybitPublicWsLike {
    private val log = LoggerFactory.getLogger(BybitPublicWs::class.java)

    private val listeners: MutableList<BybitPublicListener> = CopyOnWriteArrayList()
    private val socket: AtomicReference<WebSocket?> = AtomicReference(null)
    private val closed: AtomicBoolean = AtomicBoolean(false)

    override fun addListener(listener: BybitPublicListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: BybitPublicListener) {
        listeners.remove(listener)
    }

    fun connect() {
        if (closed.get()) error("BybitPublicWs is closed")
        val request = Request.Builder().url(url).build()
        socket.set(client.newWebSocket(request, Inner()))
    }

    override fun send(text: String) {
        val ws = socket.get() ?: error("BybitPublicWs not connected")
        ws.send(text)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.get()?.close(1000, "client close")
        }
    }

    private inner class Inner : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            listeners.forEach { runCatching { it.onConnected() } }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            val frame = BybitPublicFrame.parse(text)
            listeners.forEach { runCatching { it.onFrame(frame) } }
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            log.info("BybitPublicWs onClosed: code=$code reason=$reason")
            listeners.forEach { runCatching { it.onDisconnected("closed:$code:$reason") } }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            log.warn("BybitPublicWs onFailure: ${t.message}", t)
            listeners.forEach { runCatching { it.onDisconnected("failure:${t.message}") } }
        }
    }

    companion object {
        const val SPOT_URL = "wss://stream.bybit.com/v5/public/spot"
        const val LINEAR_URL = "wss://stream.bybit.com/v5/public/linear"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

        fun connect(url: String): BybitPublicWs = BybitPublicWs(url).apply { connect() }
    }
}
