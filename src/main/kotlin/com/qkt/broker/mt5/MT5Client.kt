package com.qkt.broker.mt5

import java.math.BigDecimal
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.slf4j.LoggerFactory

/**
 * Low-level HTTP client for the `mt5-gateway` service.
 *
 * One client per [MT5Broker]. Handles JSON serialization, retries on GETs (POST /order
 * is deliberately not retried — duplicate placement is worse than a surfaced failure),
 * timezone offsets between the venue clock and UTC, and basic error parsing.
 */
class MT5Client(
    private val gatewayUrl: String,
    private val tzOffsetHours: Int,
    private val httpTimeoutMs: Long = 5000,
    private val retryAttempts: Int = 3,
) {
    private val log = LoggerFactory.getLogger(MT5Client::class.java)
    private val tzOffsetMs: Long = tzOffsetHours.toLong() * 3600L * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(Duration.ofMillis(httpTimeoutMs))
            .connectTimeout(Duration.ofMillis(httpTimeoutMs))
            .build()

    fun isReady(): Boolean =
        runCatching {
            val resp = http.newCall(Request.Builder().url("$gatewayUrl/health").build()).execute()
            resp.use { it.isSuccessful }
        }.getOrDefault(false)

    fun placeOrder(req: MT5OrderRequest): MT5OrderResponse {
        val body = encodeOrder(req).toRequestBody(JSON_MEDIA)
        val request =
            Request
                .Builder()
                .url("$gatewayUrl/order")
                .post(body)
                .build()
        // POST /order is NOT retried: duplicate placement is worse than a surfaced failure.
        val resp = http.newCall(request).execute()
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return parseOrderResponse(raw)
        }
    }

    /**
     * Place an order WITHOUT blocking the caller. The HTTP send runs on OkHttp's dispatcher
     * (its own worker pool with a per-host cap); [onResult] is invoked on a dispatcher thread
     * with the parsed [MT5OrderResponse] on completion, or a synthetic failure response
     * (retcode -1, [MT5OrderResponse.errorMessage] set) on a non-2xx or IO error. Like
     * [placeOrder] the send is NOT retried — duplicate placement is worse than a surfaced
     * failure. This frees the engine thread from the order round-trip; the broker layer turns
     * [onResult] into the venue's `OrderAccepted`/`OrderRejected`/`OrderFilled` bus events.
     */
    fun placeOrderAsync(
        req: MT5OrderRequest,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        val body = encodeOrder(req).toRequestBody(JSON_MEDIA)
        val request =
            Request
                .Builder()
                .url("$gatewayUrl/order")
                .post(body)
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: java.io.IOException,
                ) {
                    onResult(errorResponse("IO error: ${e.message}"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    response.use {
                        val raw = it.body?.string().orEmpty()
                        val result =
                            if (it.isSuccessful) parseOrderResponse(raw) else errorResponse("HTTP ${it.code}: $raw")
                        onResult(result)
                    }
                }
            },
        )
    }

    private fun errorResponse(message: String): MT5OrderResponse =
        MT5OrderResponse(
            result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
            errorMessage = message,
        )

    /**
     * Fetch the venue's open positions. Returns `null` when the read FAILED (gateway
     * unreachable / non-2xx after retries) — callers must treat that as "unknown",
     * never as "no positions". An outage that reads as an empty account makes the
     * pollers synthesize a close for every open position (#359).
     */
    fun getPositions(magic: Int? = null): List<MT5Position>? {
        val url = if (magic != null) "$gatewayUrl/get_positions?magic=$magic" else "$gatewayUrl/get_positions"
        val raw = getWithRetry(url) ?: return null
        val arr = json.parseToJsonElement(raw).jsonArray
        return arr.map { parsePosition(it.jsonObject) }
    }

    /**
     * Fetch the venue's working (pending) orders. Returns `null` when the read FAILED
     * (gateway unreachable, non-2xx after retries, or a gateway too old to expose
     * `/orders`) — callers must treat that as "unknown", never as "all cancelled".
     */
    fun getPendingOrders(magic: Int? = null): List<MT5PendingOrder>? {
        val url = if (magic != null) "$gatewayUrl/orders?magic=$magic" else "$gatewayUrl/orders"
        val raw = getWithRetry(url) ?: return null
        // The gateway's /orders shape varies by version: some return a bare
        // array, others wrap it as {"orders": [...], "total": N}. Accept both.
        val arr =
            when (val root = json.parseToJsonElement(raw)) {
                is JsonArray -> root
                is JsonObject -> root["orders"]?.jsonArray ?: return emptyList()
                else -> return emptyList()
            }
        return arr.map { parsePendingOrder(it.jsonObject) }
    }

    /**
     * Fetch the venue's symbol metadata (volume step / min, digits, point, stops level).
     *
     * Returns `null` if the gateway doesn't expose the symbol or the call fails — the
     * caller decides whether to fall back to a configured override or pass-through.
     */
    fun getSymbolInfo(brokerSymbol: String): MT5SymbolInfo? {
        val raw = getWithRetry("$gatewayUrl/symbol_info/$brokerSymbol") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        return MT5SymbolInfo(
            ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            digits = obj["digits"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            point = obj["point"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tradeStopsLevel = obj["trade_stops_level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            volumeMin = obj["volume_min"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            volumeStep = obj["volume_step"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            // Default 1 keeps callers safe if the gateway version doesn't return the field;
            // for known instruments the venue always populates it (XAUUSD=100, EURUSD=100000).
            contractSize =
                obj["trade_contract_size"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                    ?: BigDecimal.ONE,
        )
    }

    /**
     * Fetch the account snapshot via `GET /account`. The [MT5AccountInfo.marginMode] field
     * tells the broker whether the venue is netting (`0`) or hedging (`2`) — the routing
     * decision for closing a position. Returns `null` if the call fails.
     */
    fun getAccount(): MT5AccountInfo? {
        val raw = getWithRetry("$gatewayUrl/account") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        return MT5AccountInfo(
            balance = obj["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            equity = obj["equity"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            currency = obj["currency"]?.jsonPrimitive?.contentOrNull ?: "",
            leverage = obj["leverage"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            marginMode = obj["margin_mode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: MARGIN_MODE_NETTING,
        )
    }

    /**
     * Close an open position by its venue ticket via `POST /close_position`, optionally a
     * partial [volume]. This is how a hedging account is reduced without opening a counter
     * position. The gateway wraps `order_send` underneath, so a success mirrors the
     * `POST /order` `{"result":{...}}` shape; a non-2xx (e.g. a bad ticket returns
     * `{"error":...}`) is captured in [MT5OrderResponse.errorMessage]. Not retried —
     * a duplicate close is worse than a surfaced failure.
     */
    fun closePosition(
        ticket: Long,
        volume: BigDecimal? = null,
    ): MT5OrderResponse {
        val body = encodeClosePosition(ticket, volume).toRequestBody(JSON_MEDIA)
        val request =
            Request
                .Builder()
                .url("$gatewayUrl/close_position")
                .post(body)
                .build()
        val resp = http.newCall(request).execute()
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return parseOrderResponse(raw)
        }
    }

    private fun encodeClosePosition(
        ticket: Long,
        volume: BigDecimal?,
    ): String =
        if (volume != null) {
            "{\"position\":{\"ticket\":$ticket,\"volume\":${volume.toPlainString()}}}"
        } else {
            "{\"position\":{\"ticket\":$ticket}}"
        }

    /**
     * Modify an OPEN position's SL/TP via `POST /modify_sl_tp` (gateway `TRADE_ACTION_SLTP`).
     * This is how a trailing stop keeps a venue-side stop in place — push the new SL level onto
     * the position so the broker still protects it even if qkt is offline. Returns the standard
     * `{"result":{...}}` envelope; a non-2xx is captured in [MT5OrderResponse.errorMessage].
     *
     * The gateway treats an omitted `sl`/`tp` as `0.0`, which *clears* that level. To avoid
     * clearing the take-profit when only trailing the stop, pass the current [tp] alongside [sl].
     * Not retried — a duplicate modify is harmless but a surfaced failure is preferable to silent
     * retries racing the trail.
     */
    fun modifyPosition(
        ticket: Long,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
    ): MT5OrderResponse {
        val body = encodeModifyPosition(ticket, sl, tp).toRequestBody(JSON_MEDIA)
        val request =
            Request
                .Builder()
                .url("$gatewayUrl/modify_sl_tp")
                .post(body)
                .build()
        val resp = http.newCall(request).execute()
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return parseOrderResponse(raw)
        }
    }

    private fun encodeModifyPosition(
        ticket: Long,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): String {
        val fields = mutableListOf("\"position\":$ticket")
        if (sl != null) fields += "\"sl\":${sl.toPlainString()}"
        if (tp != null) fields += "\"tp\":${tp.toPlainString()}"
        return "{" + fields.joinToString(",") + "}"
    }

    /**
     * The deals that closed venue position [positionTicket], via `GET /history_deals_get`:
     * the volume-weighted exit price plus the position's total venue costs (commission +
     * swap + fee across all its deals). This is the truth for a venue-side close (broker
     * SL/TP, manual close, stop-out) — the engine's last tick is a proxy that is stalest
     * exactly when venue-side closes happen.
     *
     * [fromUtcMs]/[toUtcMs] bound the search (the position's open time and now);
     * both are padded a day and shifted to venue time. Returns `null` when the
     * gateway can't be read or no closing deal exists in the window — callers fall
     * back to their best local proxy.
     */
    fun getClosingDeal(
        positionTicket: Long,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): MT5ClosingDeal? {
        val from = venueIso(fromUtcMs - DEAL_WINDOW_PAD_MS)
        val to = venueIso(toUtcMs + DEAL_WINDOW_PAD_MS)
        val url = "$gatewayUrl/history_deals_get?from_date=$from&to_date=$to&position=$positionTicket"
        val raw = getWithRetry(url) ?: return null
        val arr = json.parseToJsonElement(raw) as? JsonArray ?: return null
        // DEAL_ENTRY_IN (0) opened the position; OUT (1) / INOUT (2) / OUT_BY (3)
        // reduced or closed it. The close may have happened in several partial deals —
        // volume-weight them into the single price the synthesized fill carries.
        // Costs sum over ALL deals (entry + exit): MT5 books commission per deal and
        // swap on the position's deals, all signed "added to profit" (negative = cost),
        // so the charge is the negated sum.
        var volume = BigDecimal.ZERO
        var notional = BigDecimal.ZERO
        var reported = BigDecimal.ZERO
        for (el in arr) {
            val d = el.jsonObject
            val commission = d["commission"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val swap = d["swap"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val fee = d["fee"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            reported = reported.add(commission).add(swap).add(fee)
            val entry = d["entry"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (entry == 0) continue
            val price = d["price"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: continue
            val vol = d["volume"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: continue
            if (vol.signum() <= 0) continue
            volume = volume.add(vol)
            notional = notional.add(price.multiply(vol))
        }
        if (volume.signum() == 0) return null
        return MT5ClosingDeal(
            price = notional.divide(volume, com.qkt.common.Money.CONTEXT),
            costs = reported.negate(),
        )
    }

    private fun venueIso(utcMs: Long): String =
        java.time.Instant
            .ofEpochMilli(utcMs + tzOffsetMs)
            .toString()

    fun getTick(brokerSymbol: String): MT5Tick? {
        val raw = getWithRetry("$gatewayUrl/symbol_info_tick/$brokerSymbol") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        val rawTime = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return MT5Tick(
            symbol = brokerSymbol,
            bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            time = rawTime - tzOffsetMs,
        )
    }

    /**
     * Cancel a working order via `DELETE /orders/{ticket}`.
     *
     * Returns the raw response body on success, or an empty string on HTTP failure.
     * The caller logs a warning when the body indicates a non-success retcode so a
     * 404/4xx no longer masquerades as a successful cancel — the prior `POST /cancel/...`
     * shape silently succeeded against gateways that returned an HTML 404 page.
     */
    fun cancelOrder(ticket: Long): String {
        val request =
            Request
                .Builder()
                .url("$gatewayUrl/orders/$ticket")
                .delete()
                .build()
        val resp = http.newCall(request).execute()
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                log.warn("MT5Client cancelOrder($ticket) HTTP ${it.code}: $raw")
                return ""
            }
            return raw
        }
    }

    /**
     * Modify a working order via `PUT /orders/{ticket}`. Returns the gateway's
     * [MT5OrderResponse] — successful when [MT5OrderResult.retcode] is
     * `MT5_TRADE_RETCODE_DONE`. A non-2xx response is captured in [MT5OrderResponse.errorMessage]
     * so the broker layer can reject deterministically.
     */
    fun modifyOrder(
        ticket: Long,
        mods: MT5OrderModification,
    ): MT5OrderResponse {
        val body = encodeModification(mods).toRequestBody(JSON_MEDIA)
        val request =
            Request
                .Builder()
                .url("$gatewayUrl/orders/$ticket")
                .put(body)
                .build()
        val resp = http.newCall(request).execute()
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return parseOrderResponse(raw)
        }
    }

    private fun encodeModification(m: MT5OrderModification): String {
        val fields = mutableListOf<String>()
        if (m.price != null) fields += "\"price\":${m.price.toPlainString()}"
        if (m.sl != null) fields += "\"sl\":${m.sl.toPlainString()}"
        if (m.tp != null) fields += "\"tp\":${m.tp.toPlainString()}"
        if (m.slDistance != null) fields += "\"sl_distance\":${m.slDistance}"
        if (m.expiration != null) fields += "\"expiration\":${m.expiration}"
        return "{" + fields.joinToString(",") + "}"
    }

    private fun getWithRetry(url: String): String? {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= retryAttempts) {
            try {
                val resp = http.newCall(Request.Builder().url(url).build()).execute()
                resp.use {
                    if (it.isSuccessful) return it.body?.string().orEmpty()
                }
            } catch (e: java.io.IOException) {
                lastError = e
            }
            attempt++
            if (attempt <= retryAttempts) Thread.sleep(200L * attempt)
        }
        if (lastError != null) log.warn("MT5Client GET $url failed after $retryAttempts retries", lastError)
        return null
    }

    private fun encodeOrder(req: MT5OrderRequest): String {
        val sb = StringBuilder("{")

        fun field(
            name: String,
            value: String,
            last: Boolean = false,
        ) {
            sb.append("\"$name\":$value")
            if (!last) sb.append(",")
        }
        field("symbol", "\"${req.symbol}\"")
        field("volume", req.volume.toPlainString())
        field("type", "\"${req.type}\"")
        if (req.price != null) field("price", req.price.toPlainString())
        if (req.sl != null) field("sl", req.sl.toPlainString())
        if (req.tp != null) field("tp", req.tp.toPlainString())
        if (req.stopLimit != null) field("stoplimit", req.stopLimit.toPlainString())
        if (req.slDistance != null) field("sl_distance", req.slDistance.toString())
        field("deviation", req.deviation.toString())
        field("magic", req.magic.toString())
        // GTD expiry (epoch seconds). Without this a GTD pending rests GTC-forever on MT5
        // and fills late. Mirrors encodeModification, which the gateway accepts without an
        // explicit type_time — it infers TIME_SPECIFIED from the expiration's presence.
        if (req.expiration != null) field("expiration", req.expiration.toString())
        field("comment", "\"${req.comment.take(MT5_COMMENT_MAX_LENGTH)}\"", last = true)
        sb.append("}")
        return sb.toString()
    }

    private fun parseOrderResponse(raw: String): MT5OrderResponse {
        val obj = json.parseToJsonElement(raw).jsonObject
        val r =
            obj["result"]?.jsonObject
                ?: return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "missing result field: $raw",
                )
        return MT5OrderResponse(
            result =
                MT5OrderResult(
                    retcode = r["retcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1,
                    order = r["order"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    deal = r["deal"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    price = r["price"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    comment = r["comment"]?.jsonPrimitive?.contentOrNull ?: "",
                ),
            errorMessage = obj["error"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parsePosition(obj: JsonObject): MT5Position {
        val rawTime = obj["open_time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return MT5Position(
            ticket = obj["ticket"]!!.jsonPrimitive.content.toLong(),
            symbol = obj["symbol"]!!.jsonPrimitive.content,
            type = obj["type"]!!.jsonPrimitive.content.toInt(),
            volume = obj["volume"]!!.jsonPrimitive.content.toBigDecimal(),
            priceOpen = obj["price_open"]!!.jsonPrimitive.content.toBigDecimal(),
            sl = obj["sl"]!!.jsonPrimitive.content.toBigDecimal(),
            tp = obj["tp"]!!.jsonPrimitive.content.toBigDecimal(),
            profit = obj["profit"]!!.jsonPrimitive.content.toBigDecimal(),
            magic = obj["magic"]!!.jsonPrimitive.content.toInt(),
            openTime = rawTime - tzOffsetMs,
            comment = obj["comment"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * Parse one pending-order entry. Tolerant of partially-populated transient entries
     * — the gateway has been observed emitting rows mid-placement that lack a `ticket`
     * or `price_open` field. Defaulting those preserves the poller across a single bad
     * snapshot rather than killing the thread and missing all subsequent events.
     */
    private fun parsePendingOrder(obj: JsonObject): MT5PendingOrder {
        val rawTime = obj["time_setup"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val rawExp = obj["time_expiration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return MT5PendingOrder(
            ticket = obj["ticket"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            symbol = obj["symbol"]?.jsonPrimitive?.contentOrNull ?: "",
            type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "",
            volume = obj["volume"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            priceOpen = obj["price_open"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            sl = obj["sl"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tp = obj["tp"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            magic = obj["magic"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            timeSetup = rawTime - tzOffsetMs,
            timeExpiration = if (rawExp == 0L) 0L else rawExp - tzOffsetMs,
            comment = obj["comment"]?.jsonPrimitive?.contentOrNull,
        )
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Padding either side of the deal search window — venue clock skew is hours, not days. */
        private const val DEAL_WINDOW_PAD_MS: Long = 24L * 3600_000L
    }
}
