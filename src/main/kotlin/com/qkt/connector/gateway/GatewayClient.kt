package com.qkt.connector.gateway

import com.qkt.connectivity.transport.TransportCorrelation
import com.qkt.connectivity.transport.TransportJournal
import com.qkt.connectivity.transport.TransportRecorder
import java.io.IOException
import java.time.Duration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The REST half of a VGP v1 gateway (`docs/superpowers/specs/2026-10-01-vgp-v1-wire.md`) at
 * [baseUrl], authenticated by [apiKey]. Reads, and submits, are tried up to [retryAttempts] times
 * on a timeout, `503` or `429`: a submit is idempotent on its `client_order_id`, so resending the same body
 * can never place a second order. A `503` or `429` first pauses every request to the gateway on [throttle]
 * (shared per gateway); a request facing a long pause is not sent at all ([GatewayThrottle.pace]). `422`
 * and `423` refuse a submit; any other error is thrown. A request
 * may take `httpTimeoutMs` in all, its wait for the answer included. Every attempt is recorded in [journal]
 * when one is given (never the key), an order's exchanges tagged with its engine order id.
 */
class GatewayClient(
    private val baseUrl: String,
    private val apiKey: String,
    httpTimeoutMs: Long = 5_000,
    private val retryAttempts: Int = 3,
    journal: TransportJournal? = null,
    private val throttle: GatewayThrottle = GatewayThrottle(),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    private val http =
        OkHttpClient
            .Builder()
            .callTimeout(Duration.ofMillis(httpTimeoutMs))
            .connectTimeout(Duration.ofMillis(httpTimeoutMs))
            .readTimeout(Duration.ofMillis(httpTimeoutMs))
            .apply { if (journal != null) addInterceptor(TransportRecorder(journal, System::nanoTime)) }
            .build()

    init {
        require(retryAttempts >= 1) { "retryAttempts must be at least 1: $retryAttempts" }
    }

    /** `GET /v1/health`. */
    fun health(): WireHealth = read("/v1/health", WireHealth.serializer())

    /** `GET /v1/account`. */
    fun account(): WireAccount = read("/v1/account", WireAccount.serializer())

    /** `GET /v1/instruments`: every instrument the gateway trades. */
    fun instruments(): List<WireInstrument> =
        read("/v1/instruments", kotlinx.serialization.builtins.ListSerializer(WireInstrument.serializer()))

    /** `GET /v1/positions`. */
    fun positions(): WirePositions = read("/v1/positions", WirePositions.serializer())

    /** `GET /v1/orders`: the working orders. */
    fun orders(): List<WireOrder> = read("/v1/orders", WireOrders.serializer()).orders

    /** `GET /v1/deals`: executions from [fromMs] to [toMs], oldest first. */
    fun deals(
        fromMs: Long,
        toMs: Long,
    ): List<WireFill> = read("/v1/deals?from=$fromMs&to=$toMs", WireDeals.serializer()).deals

    /** `GET /v1/orders/{id}`: the order in its current state, or null when the gateway never placed it. */
    fun order(clientOrderId: String): WireOrder? {
        val (status, text) = send("/v1/orders/$clientOrderId") { it.get().forOrder(clientOrderId) }
        return when (status) {
            200 -> json.decodeFromString(WireOrder.serializer(), text)
            404 -> null
            else -> throw failure(status, text)
        }
    }

    /** `GET /v1/deals?client_order_id=`: every execution of [clientOrderId], oldest first. */
    fun dealsOf(clientOrderId: String): List<WireFill> =
        read("/v1/deals?client_order_id=$clientOrderId", WireDeals.serializer()).deals

    /** `GET /v1/settlements?symbol=`: the settlement of venue [code], empty until it expires. */
    fun settlementsOf(code: String): List<WireSettlement> =
        read("/v1/settlements?symbol=$code", WireSettlements.serializer()).settlements

    /** `GET /v1/settlements`: contract settlements from [fromMs] to [toMs], oldest first. */
    fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<WireSettlement> = read("/v1/settlements?from=$fromMs&to=$toMs", WireSettlements.serializer()).settlements

    /** `GET /v1/bars`: every closed [windowMs] bar of venue [code] starting in `[fromMs, toMs)`, oldest first. */
    fun bars(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<WireBar> {
        val bars = ArrayList<WireBar>()
        var from: Long? = fromMs
        while (from != null && from < toMs) {
            val page = read("/v1/bars?symbol=$code&window_ms=$windowMs&from=$from&to=$toMs", WireBars.serializer())
            bars += page.bars
            require(page.next == null || page.next > from) { "gateway bars of $code do not advance past $from" }
            from = page.next
        }
        return bars
    }

    /** `POST /v1/orders`; see the class note on retries and refusals. */
    fun submit(order: WireSubmit): GatewaySubmit {
        val body = json.encodeToString(WireSubmit.serializer(), order)
        val (status, text) = send("/v1/orders") { it.post(body.toRequestBody(JSON)).forOrder(order.clientOrderId) }
        return when (status) {
            200, 201 -> GatewaySubmit.Placed(json.decodeFromString(WireOrder.serializer(), text))
            422, 423 -> errorOf(status, text).let { GatewaySubmit.Refused(it.code, it.message) }
            else -> throw failure(status, text)
        }
    }

    /** `DELETE /v1/orders/{id}`: the order as it ended, `filled` when the fill won the race. */
    fun cancel(clientOrderId: String): WireOrder =
        write("/v1/orders/$clientOrderId") { it.delete().forOrder(clientOrderId) }

    private fun Request.Builder.forOrder(clientOrderId: String) =
        tag(TransportCorrelation::class.java, TransportCorrelation(GatewayClientIds.engineId(clientOrderId)))

    internal fun write(
        path: String,
        method: (Request.Builder) -> Request.Builder,
    ): WireOrder {
        val (status, text) = send(path, method)
        if (status != 200 && status != 201) throw failure(status, text)
        return json.decodeFromString(WireOrder.serializer(), text)
    }

    internal fun <T> read(
        path: String,
        serializer: KSerializer<T>,
    ): T {
        val (status, text) = send(path) { it.get() }
        if (status != 200) throw failure(status, text)
        return json.decodeFromString(serializer, text)
    }

    /** How long until the gateway may be asked again after a rate-limited answer; 0 when it may be now. */
    fun backoffMs(): Long = throttle.waitMs()

    /** One request, sent again on a timeout, `503` or `429` up to [retryAttempts] times, paced by [throttle]. */
    private fun send(
        path: String,
        method: (Request.Builder) -> Request.Builder,
    ): Pair<Int, String> {
        var last: String? = null
        repeat(retryAttempts) {
            throttle.pace(path, last)
            val request =
                method(
                    Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $apiKey"),
                ).build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code != 503 && response.code != 429) {
                        throttle.answered()
                        return response.code to text
                    }
                    throttle.limited(response.header("Retry-After"))
                    last = "${response.code} ${errorOf(response.code, text).message}"
                }
            } catch (e: IOException) {
                last = e.toString()
            }
        }
        throw GatewayUnavailableException("$path: no answer after $retryAttempts attempts ($last)")
    }

    private fun errorOf(
        status: Int,
        text: String,
    ): WireError =
        runCatching { json.decodeFromString(WireErrorBody.serializer(), text).error }
            .getOrElse { WireError("http_$status", text.take(200)) }

    private fun failure(
        status: Int,
        text: String,
    ): GatewayException = errorOf(status, text).let { GatewayException(status, it.code, it.message) }

    internal companion object {
        val JSON = "application/json".toMediaType()
    }
}
