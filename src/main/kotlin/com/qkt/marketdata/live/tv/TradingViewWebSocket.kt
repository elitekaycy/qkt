package com.qkt.marketdata.live.tv

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.slf4j.LoggerFactory

class TradingViewWebSocket(
    private val url: String = DEFAULT_URL,
    private val origin: String = DEFAULT_ORIGIN,
    private val authToken: String = ANONYMOUS_TOKEN,
    private val client: OkHttpClient = defaultClient(),
) {
    private val log = LoggerFactory.getLogger(TradingViewWebSocket::class.java)

    private val listeners: MutableList<TradingViewListener> = CopyOnWriteArrayList()
    private val socket: AtomicReference<WebSocket?> = AtomicReference(null)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val buffer = StringBuilder()

    fun addListener(listener: TradingViewListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TradingViewListener) {
        listeners.remove(listener)
    }

    fun connect() {
        if (closed.get()) error("TradingViewWebSocket is closed")
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Origin", origin)
                .build()
        val webSocket = client.newWebSocket(request, InternalListener())
        socket.set(webSocket)
    }

    fun send(
        method: String,
        params: List<Any>,
    ) {
        val ws = socket.get() ?: error("TradingViewWebSocket is not connected")
        val message =
            TradingViewFrame.Message(
                method = method,
                params = paramsToJsonArray(params),
            )
        val framed = TradingViewFraming.encode(message.toWireJson())
        ws.send(framed)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.get()?.close(1000, "client close")
        }
    }

    private fun paramsToJsonArray(params: List<Any>): JsonArray =
        buildJsonArray {
            params.forEach { add(toJsonElement(it)) }
        }

    private fun toJsonElement(value: Any): JsonElement =
        when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonElement -> value
            is List<*> ->
                buildJsonArray {
                    value.forEach { add(toJsonElement(it ?: error("null param element"))) }
                }
            else -> error("Unsupported parameter type: ${value::class.java.simpleName}")
        }

    private fun handleHeartbeat(seq: Int) {
        val ws = socket.get() ?: return
        val response = TradingViewFraming.encode("~h~$seq~h~")
        ws.send(response)
    }

    private inner class InternalListener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            send("set_auth_token", listOf(authToken))
            listeners.forEach { runCatching { it.onConnected() } }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
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
            val reason = "failure: ${t.message}"
            log.warn("TradingViewWebSocket onFailure: $reason", t)
            listeners.forEach { runCatching { it.onDisconnected(reason) } }
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            log.info("TradingViewWebSocket onClosed: code=$code reason=$reason")
            listeners.forEach { runCatching { it.onDisconnected("closed:$code:$reason") } }
        }

        private fun dispatch(payload: String) {
            try {
                val frame = TradingViewFrame.parse(payload)
                if (frame is TradingViewFrame.Heartbeat) {
                    handleHeartbeat(frame.seq)
                }
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
            client: OkHttpClient = defaultClient(),
        ): TradingViewWebSocket = TradingViewWebSocket(url, origin, authToken, client).apply { connect() }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
    }
}
