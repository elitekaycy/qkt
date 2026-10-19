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
 * A quote of venue [code] from the quotes socket (VGP v1 has no REST quote). Updates may carry one side
 * each, so the latest bid, ask and mark are merged: the answer comes once both sides are known, or, at
 * most [sideWaitMs] after the first price, with the missing side taken from the venue's mark (a one-sided
 * book). Throws when no price arrives within [timeoutMs]: a one-shot order never prices a made-up quote.
 */
fun gatewayQuoteOnce(
    baseUrl: String,
    apiKey: String,
    code: String,
    timeoutMs: Long = 10_000,
    sideWaitMs: Long = 2_000,
): WireQuote {
    val url =
        baseUrl
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("v1/quotes")
            .addQueryParameter("symbols", code)
            .build()
            .toString()
    val latest =
        java.util.concurrent.atomic
            .AtomicReference<WireQuote?>(null)
    val firstPrice = java.util.concurrent.CountDownLatch(1)
    val twoSided = CompletableFuture<WireQuote>()
    val socket =
        GatewaySocket(
            "quote-once",
            apiKey,
            { url },
            onText = { text ->
                val q = runCatching { quoteJson.decodeFromString(WireQuote.serializer(), text) }.getOrNull()
                if (q != null && q.symbol == code) {
                    val merged =
                        latest.updateAndGet { old ->
                            WireQuote(
                                code,
                                q.bid ?: old?.bid,
                                q.ask ?: old?.ask,
                                mark = q.mark ?: old?.mark,
                                time = q.time,
                            )
                        }!!
                    if (merged.bid != null || merged.ask != null) firstPrice.countDown()
                    if (merged.bid != null && merged.ask != null) twoSided.complete(merged)
                }
            },
            onConnection = { _, _ -> },
            initialBackoffMs = 500,
            maxBackoffMs = 2_000,
        )
    socket.start()
    try {
        if (!firstPrice.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            error("no quote for $code within ${timeoutMs / 1000.0} s; is the gateway streaming it?")
        }
        return try {
            twoSided.get(sideWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            val q = latest.get()!!
            val mark =
                q.mark ?: error("$code has a one-sided book (bid ${q.bid}, ask ${q.ask}) and no mark to price against")
            q.copy(bid = q.bid ?: mark, ask = q.ask ?: mark)
        }
    } finally {
        socket.stop()
    }
}
