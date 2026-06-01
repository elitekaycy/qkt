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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    fun getPositions(magic: Int? = null): List<MT5Position> {
        val url = if (magic != null) "$gatewayUrl/get_positions?magic=$magic" else "$gatewayUrl/get_positions"
        val raw = getWithRetry(url) ?: return emptyList()
        val arr = json.parseToJsonElement(raw).jsonArray
        return arr.map { parsePosition(it.jsonObject) }
    }

    /**
     * Fetch the venue's working (pending) orders. Empty list if the gateway doesn't
     * expose `/orders` (returns 404 → handled by [getWithRetry] returning null).
     */
    fun getPendingOrders(magic: Int? = null): List<MT5PendingOrder> {
        val url = if (magic != null) "$gatewayUrl/orders?magic=$magic" else "$gatewayUrl/orders"
        val raw = getWithRetry(url) ?: return emptyList()
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
    }
}
