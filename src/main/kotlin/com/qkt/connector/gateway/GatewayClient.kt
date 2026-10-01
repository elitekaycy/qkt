package com.qkt.connector.gateway

import java.io.IOException
import java.time.Duration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What a gateway answered to a submit: the order it placed, or its refusal (`venue_rejected`, `kill_switch`). */
sealed interface GatewaySubmit {
    /** The gateway holds [order]: new, or the existing one for a resubmitted `client_order_id`. */
    data class Placed(
        val order: WireOrder,
    ) : GatewaySubmit

    /** The venue or the kill switch refused the order, with the gateway's [code] and [message]. */
    data class Refused(
        val code: String,
        val message: String,
    ) : GatewaySubmit
}

/** The gateway answered [status] with error [code]: a request it will not serve as sent. */
class GatewayException(
    val status: Int,
    val code: String,
    message: String,
) : RuntimeException("$code: $message")

/** The gateway could not be reached, or could not reach its venue, within the configured attempts. */
class GatewayUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The REST half of a VGP v1 gateway (`docs/superpowers/specs/2026-10-01-vgp-v1-wire.md`) at
 * [baseUrl], authenticated by [apiKey]. Reads, and submits, are tried up to [retryAttempts] times
 * on a timeout or `503`: a submit is idempotent on its `client_order_id`, so resending the same body
 * can never place a second order. `422` and `423` refuse a submit; any other error is thrown.
 */
class GatewayClient(
    private val baseUrl: String,
    private val apiKey: String,
    httpTimeoutMs: Long = 5_000,
    private val retryAttempts: Int = 3,
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

    /** `GET /v1/settlements`: contract settlements from [fromMs] to [toMs], oldest first. */
    fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<WireSettlement> = read("/v1/settlements?from=$fromMs&to=$toMs", WireSettlements.serializer()).settlements

    /** `POST /v1/orders`; see the class note on retries and refusals. */
    fun submit(order: WireSubmit): GatewaySubmit {
        val body = json.encodeToString(WireSubmit.serializer(), order)
        val (status, text) = send("/v1/orders") { it.post(body.toRequestBody(JSON)) }
        return when (status) {
            200, 201 -> GatewaySubmit.Placed(json.decodeFromString(WireOrder.serializer(), text))
            422, 423 -> errorOf(status, text).let { GatewaySubmit.Refused(it.code, it.message) }
            else -> throw failure(status, text)
        }
    }

    /** `DELETE /v1/orders/{id}`: the order as it ended, `filled` when the fill won the race. */
    fun cancel(clientOrderId: String): WireOrder = write("/v1/orders/$clientOrderId") { it.delete() }

    /** `POST /v1/positions/close`: closes [quantity] of [symbol], or all of it; never gated. */
    fun closePosition(
        symbol: String,
        quantity: String? = null,
    ): WireOrder {
        val body = json.encodeToString(ClosePosition.serializer(), ClosePosition(symbol, quantity))
        return write("/v1/positions/close") { it.post(body.toRequestBody(JSON)) }
    }

    private fun write(
        path: String,
        method: (Request.Builder) -> Request.Builder,
    ): WireOrder {
        val (status, text) = send(path, method)
        if (status != 200 && status != 201) throw failure(status, text)
        return json.decodeFromString(WireOrder.serializer(), text)
    }

    private fun <T> read(
        path: String,
        serializer: KSerializer<T>,
    ): T {
        val (status, text) = send(path) { it.get() }
        if (status != 200) throw failure(status, text)
        return json.decodeFromString(serializer, text)
    }

    /** One request, sent again on a timeout or `503` up to [retryAttempts] times. */
    private fun send(
        path: String,
        method: (Request.Builder) -> Request.Builder,
    ): Pair<Int, String> {
        var last: String? = null
        repeat(retryAttempts) {
            val request =
                method(
                    Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $apiKey"),
                ).build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code != 503) return response.code to text
                    last = "503 ${errorOf(503, text).message}"
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

    @kotlinx.serialization.Serializable
    private data class ClosePosition(
        val symbol: String,
        val quantity: String? = null,
    )

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
