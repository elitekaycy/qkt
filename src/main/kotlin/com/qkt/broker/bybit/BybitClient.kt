package com.qkt.broker.bybit

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory

class BybitApiException(
    val retCode: Int,
    val retMsg: String,
) : RuntimeException("Bybit retCode=$retCode retMsg=$retMsg")

interface BybitTransport {
    fun postSigned(
        path: String,
        jsonBody: String,
    ): String

    fun subscribe(
        topic: String,
        listener: (JsonObject) -> Unit,
    )

    fun onDisconnect(handler: (String) -> Unit)
}

class BybitClient(
    apiKey: String? = null,
    apiSecret: String? = null,
    testnet: Boolean? = null,
    recvWindowMs: Long? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clock: Clock = SystemClock(),
) : BybitTransport {
    private val log = LoggerFactory.getLogger(BybitClient::class.java)

    private val resolvedApiKey: String =
        apiKey
            ?: System.getenv("BYBIT_API_KEY")
            ?: error("Bybit API key required: pass apiKey=... or set BYBIT_API_KEY env var")

    private val resolvedApiSecret: String =
        apiSecret
            ?: System.getenv("BYBIT_API_SECRET")
            ?: error("Bybit API secret required: pass apiSecret=... or set BYBIT_API_SECRET env var")

    private val resolvedTestnet: Boolean =
        testnet
            ?: (System.getenv("BYBIT_TESTNET")?.equals("false", ignoreCase = true)?.let { !it })
            ?: true

    private val resolvedRecvWindowMs: Long =
        recvWindowMs
            ?: System.getenv("BYBIT_RECV_WINDOW_MS")?.toLongOrNull()
            ?: 5_000L

    private val signer = BybitSigner(resolvedApiSecret)

    val restBaseUrl: String =
        if (resolvedTestnet) "https://api-testnet.bybit.com" else "https://api.bybit.com"

    val wsPrivateUrl: String =
        if (resolvedTestnet) {
            "wss://stream-testnet.bybit.com/v5/private"
        } else {
            "wss://stream.bybit.com/v5/private"
        }

    private val wsRef: AtomicReference<WebSocket?> = AtomicReference(null)
    private val topicListeners: MutableMap<String, MutableList<(JsonObject) -> Unit>> = mutableMapOf()
    private val onDisconnectListeners: MutableList<(String) -> Unit> = CopyOnWriteArrayList()
    private val pingExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bybit-ws-ping").apply { isDaemon = true }
        }
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingSubscribeTopics: MutableSet<String> = mutableSetOf()

    override fun postSigned(
        path: String,
        jsonBody: String,
    ): String {
        val timestamp = clock.now().toString()
        val recvWindow = resolvedRecvWindowMs.toString()
        val preSign = timestamp + resolvedApiKey + recvWindow + jsonBody
        val signature = signer.signHex(preSign)

        val req =
            Request
                .Builder()
                .url("$restBaseUrl$path")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .addHeader("X-BAPI-API-KEY", resolvedApiKey)
                .addHeader("X-BAPI-TIMESTAMP", timestamp)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-RECV-WINDOW", recvWindow)
                .build()

        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("empty Bybit response (HTTP ${resp.code})")
            if (!resp.isSuccessful) {
                throw BybitApiException(retCode = resp.code, retMsg = "HTTP ${resp.code}: $body")
            }
            return body
        }
    }

    override fun subscribe(
        topic: String,
        listener: (JsonObject) -> Unit,
    ) {
        synchronized(topicListeners) {
            topicListeners.getOrPut(topic) { mutableListOf() }.add(listener)
            pendingSubscribeTopics.add(topic)
        }
    }

    override fun onDisconnect(handler: (String) -> Unit) {
        onDisconnectListeners.add(handler)
    }

    fun connect() {
        if (wsRef.get() != null) return

        val req = Request.Builder().url(wsPrivateUrl).build()
        val ws =
            httpClient.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        sendAuth(webSocket)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        onWsMessage(text)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        log.warn("Bybit WS onFailure: {}", t.message)
                        onWsDisconnect("failure: ${t.message}")
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        log.info("Bybit WS onClosed: code={} reason={}", code, reason)
                        onWsDisconnect("closed: $code $reason")
                    }
                },
            )
        wsRef.set(ws)
        startPingScheduler()
    }

    fun close() {
        pingExecutor.shutdownNow()
        wsRef.getAndSet(null)?.close(1000, "client close")
    }

    private fun sendAuth(ws: WebSocket) {
        val expires = clock.now() + 10_000
        val toSign = "GET/realtime$expires"
        val signature = signer.signHex(toSign)
        val authMsg =
            """{"op":"auth","args":["$resolvedApiKey",$expires,"$signature"]}"""
        ws.send(authMsg)
        synchronized(topicListeners) {
            if (pendingSubscribeTopics.isNotEmpty()) {
                val args = pendingSubscribeTopics.joinToString(",") { "\"$it\"" }
                ws.send("""{"op":"subscribe","args":[$args]}""")
            }
        }
    }

    private fun onWsMessage(text: String) {
        val tree = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val topic = tree["topic"]?.jsonPrimitive?.content
        if (topic != null) {
            synchronized(topicListeners) {
                topicListeners[topic]?.forEach { runCatching { it(tree) } }
            }
        }
        val op = tree["op"]?.jsonPrimitive?.content
        if (op != null) {
            log.debug("Bybit WS op response: {}", text)
        }
    }

    private fun onWsDisconnect(reason: String) {
        wsRef.set(null)
        onDisconnectListeners.forEach { runCatching { it(reason) } }
    }

    private fun startPingScheduler() {
        pingExecutor.scheduleAtFixedRate(
            {
                wsRef.get()?.send("""{"op":"ping"}""")
            },
            20_000L,
            20_000L,
            TimeUnit.MILLISECONDS,
        )
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
    }
}
