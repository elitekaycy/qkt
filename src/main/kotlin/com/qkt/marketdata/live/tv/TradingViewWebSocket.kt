package com.qkt.marketdata.live.tv

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.slf4j.LoggerFactory

/**
 * The one WebSocket to TradingView that chart and quote sessions share. A dropped connection is
 * re-opened with backoff ([reconnectDelayMs] per attempt) until [close]; listeners see
 * `onDisconnected` and then `onConnected` again, which is where sessions re-create themselves. A
 * connection that has gone silent for [staleAfterMs] (TradingView heartbeats every ~10s) is dropped
 * the same way, since without WebSocket pings nothing else would notice a half-open socket; [clock]
 * times that silence.
 */
class TradingViewWebSocket(
    private val url: String = DEFAULT_URL,
    private val origin: String = DEFAULT_ORIGIN,
    private val authToken: String = ANONYMOUS_TOKEN,
    private val client: OkHttpClient = TradingViewConnection.defaultClient(),
    private val reconnectDelayMs: (attempt: Int) -> Long = TradingViewConnection::backoffMs,
    private val staleAfterMs: Long = 30_000L,
    private val clock: Clock = SystemClock(),
) : TradingViewWebSocketLike {
    private val log = LoggerFactory.getLogger(TradingViewWebSocket::class.java)

    private val listeners: MutableList<TradingViewListener> = CopyOnWriteArrayList()
    private val socket: AtomicReference<WebSocket?> = AtomicReference(null)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val attempts = AtomicInteger(0)
    private val lastFrameAtMs = AtomicLong(clock.now())
    private val buffer = StringBuilder()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tradingview-reconnect").apply { isDaemon = true } }

    override fun addListener(listener: TradingViewListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TradingViewListener) {
        listeners.remove(listener)
    }

    fun connect() {
        if (closed.get()) error("TradingViewWebSocket is closed")
        open()
        val checkMs = (staleAfterMs / 3).coerceAtLeast(1L)
        scheduler.scheduleWithFixedDelay(::dropIfStale, checkMs, checkMs, TimeUnit.MILLISECONDS)
    }

    override fun send(
        method: String,
        params: List<Any>,
    ) {
        val ws = socket.get() ?: error("TradingViewWebSocket is not connected")
        val message = TradingViewFrame.Message(method = method, params = tradingViewParams(params))
        ws.send(TradingViewFraming.encode(message.toWireJson()))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow()
            socket.get()?.close(1000, "client close")
        }
    }

    private fun open() {
        synchronized(buffer) { buffer.setLength(0) }
        lastFrameAtMs.set(clock.now())
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Origin", origin)
                .build()
        socket.set(client.newWebSocket(request, InternalListener()))
    }

    private fun dropIfStale() {
        val ws = socket.get() ?: return
        val silentMs = clock.now() - lastFrameAtMs.get()
        if (!closed.get() && silentMs > staleAfterMs) {
            log.warn("TradingViewWebSocket silent for {}ms; dropping it to reconnect", silentMs)
            ws.cancel()
        }
    }

    // One reconnect per lost connection: OkHttp reports a socket's end once, through either callback.
    private fun lost(
        webSocket: WebSocket,
        reason: String,
    ) {
        if (socket.get() !== webSocket) return
        listeners.forEach { runCatching { it.onDisconnected(reason) } }
        if (closed.get()) return
        val attempt = attempts.incrementAndGet()
        val delay = reconnectDelayMs(attempt)
        log.warn("TradingViewWebSocket lost ({}); reconnect attempt {} in {}ms", reason, attempt, delay)
        runCatching {
            scheduler.schedule({ if (!closed.get()) open() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    // TradingView checks liveness with its `~h~N` frame; a live client sends it back unchanged.
    private fun handleHeartbeat(payload: String) {
        val ws = socket.get() ?: return
        ws.send(TradingViewFraming.encode(payload))
    }

    private inner class InternalListener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            if (socket.get() !== webSocket) return
            attempts.set(0)
            send("set_auth_token", listOf(authToken))
            listeners.forEach { runCatching { it.onConnected() } }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (socket.get() !== webSocket) return
            lastFrameAtMs.set(clock.now())
            synchronized(buffer) {
                buffer.append(text)
                val decoded = TradingViewFraming.decodeAll(buffer.toString())
                buffer.setLength(0)
                buffer.append(decoded.leftover)
                decoded.frames.forEach { dispatch(it) }
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (!closed.get()) log.warn("TradingViewWebSocket onFailure: ${t.message}", t)
            lost(webSocket, "failure: ${t.message}")
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            log.info("TradingViewWebSocket onClosed: code=$code reason=$reason")
            lost(webSocket, "closed:$code:$reason")
        }

        private fun dispatch(payload: String) {
            try {
                val frame = TradingViewFrame.parse(payload)
                if (frame is TradingViewFrame.Heartbeat) handleHeartbeat(payload)
                listeners.forEach { runCatching { it.onFrame(frame) } }
            } catch (e: TradingViewProtocolException) {
                log.warn("Cannot parse frame: $payload", e)
            }
        }
    }

    companion object {
        const val DEFAULT_URL = "wss://data.tradingview.com/socket.io/websocket"
        const val DEFAULT_ORIGIN = "https://www.tradingview.com"
        const val ANONYMOUS_TOKEN = "unauthorized_user_token"

        fun connect(
            url: String = DEFAULT_URL,
            origin: String = DEFAULT_ORIGIN,
            authToken: String = ANONYMOUS_TOKEN,
            client: OkHttpClient = TradingViewConnection.defaultClient(),
        ): TradingViewWebSocket = TradingViewWebSocket(url, origin, authToken, client).apply { connect() }
    }
}
