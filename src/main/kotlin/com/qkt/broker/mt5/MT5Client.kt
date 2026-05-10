package com.qkt.broker.mt5

import java.math.BigDecimal
import java.time.Duration
import kotlinx.serialization.json.Json
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
        val url = if (magic != null) "$gatewayUrl/positions?magic=$magic" else "$gatewayUrl/positions"
        val raw = getWithRetry(url) ?: return emptyList()
        val arr = json.parseToJsonElement(raw).jsonArray
        return arr.map { parsePosition(it.jsonObject) }
    }

    fun getTick(brokerSymbol: String): MT5Tick? {
        val url = "$gatewayUrl/tick?symbol=$brokerSymbol"
        val raw = getWithRetry(url) ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        val rawTime = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return MT5Tick(
            symbol = brokerSymbol,
            bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            time = rawTime - tzOffsetMs,
        )
    }

    fun cancelOrder(ticket: Long): String {
        val request =
            Request
                .Builder()
                .url("$gatewayUrl/cancel/$ticket")
                .post("".toRequestBody(JSON_MEDIA))
                .build()
        val resp = http.newCall(request).execute()
        return resp.use { it.body?.string().orEmpty() }
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
        field("deviation", req.deviation.toString())
        field("magic", req.magic.toString())
        field("comment", "\"${req.comment}\"", last = true)
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

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
