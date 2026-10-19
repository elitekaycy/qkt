package com.qkt.connector.gateway

import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody

// The VGP v1 calls a one-shot `qkt bot` command needs beside the engine's: flatten a position, amend a
// working order, and read one quote. Each is a single request a short-lived process makes and forgets.

/**
 * `POST /v1/positions/close`: a reduce-only order for the other side of venue [code]'s position, all of it
 * when [quantity] is null. Idempotent on [clientOrderId], so a timeout resends the same body and never
 * closes twice. Never gated by the kill switch.
 */
fun GatewayClient.closePosition(
    code: String,
    quantity: BigDecimal?,
    clientOrderId: String,
): WireOrder {
    val body =
        buildJsonObject {
            put("symbol", JsonPrimitive(code))
            quantity?.let { put("quantity", JsonPrimitive(it.toPlainString())) }
            put("client_order_id", JsonPrimitive(clientOrderId))
        }.toString()
    return write("/v1/positions/close") { it.post(body.toRequestBody(GatewayClient.JSON)) }
}

/** `PATCH /v1/orders/{id}`: moves a working order's quantity or prices; only the given fields are sent. */
fun GatewayClient.amend(
    clientOrderId: String,
    quantity: BigDecimal?,
    limitPrice: BigDecimal?,
    stopPrice: BigDecimal?,
): WireOrder {
    require(quantity != null || limitPrice != null || stopPrice != null) { "amend needs a quantity or a price" }
    val body =
        buildJsonObject {
            quantity?.let { put("quantity", JsonPrimitive(it.toPlainString())) }
            limitPrice?.let { put("limit_price", JsonPrimitive(it.toPlainString())) }
            stopPrice?.let { put("stop_price", JsonPrimitive(it.toPlainString())) }
        }.toString()
    return write("/v1/orders/$clientOrderId") { it.patch(body.toRequestBody(GatewayClient.JSON)) }
}

private val quoteJson = Json { ignoreUnknownKeys = true }

/**
 * The first quote of venue [code] with a bid or an ask, from the quotes socket (VGP v1 has no REST quote).
 * Throws when none arrives within [timeoutMs]: a one-shot order never prices against a made-up quote.
 */
fun gatewayQuoteOnce(
    baseUrl: String,
    apiKey: String,
    code: String,
    timeoutMs: Long = 10_000,
): WireQuote {
    val url =
        baseUrl
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("v1/quotes")
            .addQueryParameter("symbols", code)
            .build()
            .toString()
    val first = CompletableFuture<WireQuote>()
    val socket =
        GatewaySocket(
            "quote-once",
            apiKey,
            { url },
            onText = { text ->
                runCatching { quoteJson.decodeFromString(WireQuote.serializer(), text) }
                    .getOrNull()
                    ?.takeIf { it.symbol == code && (it.bid != null || it.ask != null) }
                    ?.let { first.complete(it) }
            },
            onConnection = { _, _ -> },
            initialBackoffMs = 500,
            maxBackoffMs = 2_000,
        )
    socket.start()
    return try {
        first.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        error("no quote for $code within ${timeoutMs / 1000.0} s; is the gateway streaming it?")
    } finally {
        socket.stop()
    }
}
