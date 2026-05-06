package com.qkt.broker.bybit

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.net.ExponentialBackoff
import com.qkt.common.net.ReconnectSupervisor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

class BybitConnectException(
    message: String,
) : RuntimeException(message)

interface BybitTransport {
    val isConnected: Boolean

    val balances: Map<String, java.math.BigDecimal>

    fun updateBalances(snapshot: Map<String, java.math.BigDecimal>)

    fun postSigned(
        path: String,
        jsonBody: String,
    ): String

    fun subscribe(
        topic: String,
        listener: (JsonObject) -> Unit,
    )

    fun onDisconnect(handler: (String) -> Unit)

    fun onReconnect(handler: () -> Unit)
}

class BybitClient(
    apiKey: String? = null,
    apiSecret: String? = null,
    testnet: Boolean? = null,
    recvWindowMs: Long? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clock: Clock = SystemClock(),
    private val wsFactory: (Request, WebSocketListener) -> WebSocket =
        { req, listener -> httpClient.newWebSocket(req, listener) },
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
    private val onReconnectListeners: MutableList<() -> Unit> = CopyOnWriteArrayList()
    private val pingExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bybit-ws-ping").apply { isDaemon = true }
        }
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingSubscribeTopics: MutableSet<String> = mutableSetOf()

    private val connected: AtomicBoolean = AtomicBoolean(false)
    private val hasEverConnected: AtomicBoolean = AtomicBoolean(false)

    @Volatile
    private var authLatch: CountDownLatch? = null

    private val supervisor: ReconnectSupervisor =
        ReconnectSupervisor(
            backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L),
            attemptReconnect = { attemptReconnect() },
            onReconnected = { fireOnReconnect() },
        )

    override val isConnected: Boolean get() = connected.get()

    private val balancesRef: AtomicReference<Map<String, java.math.BigDecimal>> = AtomicReference(emptyMap())

    override val balances: Map<String, java.math.BigDecimal>
        get() = balancesRef.get()

    override fun updateBalances(snapshot: Map<String, java.math.BigDecimal>) {
        balancesRef.set(snapshot.toMap())
    }

    override fun onReconnect(handler: () -> Unit) {
        onReconnectListeners.add(handler)
    }

    override fun postSigned(
        path: String,
        jsonBody: String,
    ): String {
        var attempt = 0
        val maxAttempts = 3
        var lastEx: Exception? = null
        while (attempt < maxAttempts) {
            attempt++
            try {
                return doPostSignedOnce(path, jsonBody)
            } catch (e: BybitApiException) {
                throw e
            } catch (e: Exception) {
                lastEx = e
                log.warn("postSigned attempt {} for {} failed: {}", attempt, path, e.message)
                if (attempt < maxAttempts) {
                    Thread.sleep(transportRetryDelayMs(attempt))
                }
            }
        }
        throw lastEx ?: error("postSigned exhausted retries with no captured exception")
    }

    private fun doPostSignedOnce(
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

    private fun transportRetryDelayMs(attempt: Int): Long =
        when (attempt) {
            1 -> 500L
            2 -> 1_000L
            else -> 2_000L
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

        authLatch = CountDownLatch(1)
        val req = Request.Builder().url(wsPrivateUrl).build()
        val ws = wsFactory(req, makeWsListener())
        wsRef.set(ws)
        startPingScheduler()

        runCatching {
            authLatch!!.await(10_000L, TimeUnit.MILLISECONDS)
        }
        if (connected.get()) {
            hasEverConnected.set(true)
        } else {
            wsRef.getAndSet(null)?.close(1000, "initial connect failed")
            pingExecutor.shutdownNow()
            throw BybitConnectException(
                "Initial Bybit connect failed within 10s (auth ack not received). " +
                    "Check BYBIT_API_KEY / BYBIT_API_SECRET and BYBIT_TESTNET flag.",
            )
        }
    }

    fun close() {
        supervisor.abort()
        pingExecutor.shutdownNow()
        wsRef.getAndSet(null)?.close(1000, "client close")
        connected.set(false)
    }

    private fun attemptReconnect(): Boolean {
        try {
            authLatch = CountDownLatch(1)
            val req = Request.Builder().url(wsPrivateUrl).build()
            val ws = wsFactory(req, makeWsListener())
            wsRef.set(ws)
            val authed = authLatch!!.await(10_000L, TimeUnit.MILLISECONDS)
            return authed && connected.get()
        } catch (e: Exception) {
            log.warn("attemptReconnect threw: {}", e.message)
            return false
        }
    }

    private fun makeWsListener(): WebSocketListener =
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
                connected.set(false)
                wsRef.set(null)
                onWsDisconnect("failure: ${t.message}")
                if (hasEverConnected.get()) supervisor.scheduleReconnect()
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                log.info("Bybit WS onClosed: code={} reason={}", code, reason)
                connected.set(false)
                wsRef.set(null)
                onWsDisconnect("closed: $code $reason")
                if (hasEverConnected.get()) supervisor.scheduleReconnect()
            }
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
        if (op == "auth") {
            val success = tree["success"]?.jsonPrimitive?.content?.toBoolean() ?: false
            if (success) {
                connected.set(true)
                authLatch?.countDown()
            } else {
                log.warn("Bybit auth failed: {}", text)
            }
        }
        if (op != null) {
            log.debug("Bybit WS op response: {}", text)
        }
    }

    private fun onWsDisconnect(reason: String) {
        onDisconnectListeners.forEach { runCatching { it(reason) } }
    }

    private fun fireOnReconnect() {
        onReconnectListeners.forEach { runCatching { it() } }
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
