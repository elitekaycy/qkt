package com.qkt.marketdata.live.bybit

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches historical klines from Bybit's REST endpoint `/v5/market/kline`.
 *
 * [category] is `"spot"` or `"linear"`. The Bybit API returns klines newest-first; this
 * client reverses them to ascending order before yielding. For ranges larger than one
 * page (max [pageLimit]=1000), repeats requests with shifted `start`.
 */
class BybitKlineClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val category: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val pageLimit: Int = 1000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchRange(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val interval = windowToInterval(window)
        val durationMs = window.durationMs
        val rangeStart = range.from.toEpochMilli()
        val rangeEnd = range.to.toEpochMilli()
        return sequence {
            var cursor = rangeStart
            while (cursor < rangeEnd) {
                val pageEnd = (cursor + pageLimit * durationMs - 1).coerceAtMost(rangeEnd)
                val page = fetchPage(symbol, interval, durationMs, cursor, pageEnd)
                if (page.isEmpty()) return@sequence
                for (c in page) {
                    if (c.startTime >= rangeStart && c.startTime < rangeEnd) yield(c)
                }
                val last = page.last().startTime
                if (last <= cursor) return@sequence
                cursor = last + durationMs
            }
        }
    }

    private fun fetchPage(
        symbol: String,
        interval: String,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): List<Candle> {
        val url =
            "$baseUrl/v5/market/kline?category=$category&symbol=$symbol&interval=$interval" +
                "&start=$startMs&end=$endMs&limit=$pageLimit"
        val req = Request.Builder().url(url).build()
        val raw =
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Bybit kline HTTP ${resp.code} for $url: ${resp.body?.string()}" }
                resp.body?.string() ?: error("Bybit kline empty body for $url")
            }
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: error("Bybit kline non-object: $raw")
        val result = obj["result"]?.jsonObject ?: error("Bybit kline missing result: $raw")
        val list = result["list"]?.jsonArray ?: return emptyList()
        return list
            .map { row ->
                val v = row.jsonArray
                val startTime = v[0].jsonPrimitive.content.toLong()
                Candle(
                    symbol = symbol,
                    open = v[1].jsonPrimitive.content.toBigDecimal(),
                    high = v[2].jsonPrimitive.content.toBigDecimal(),
                    low = v[3].jsonPrimitive.content.toBigDecimal(),
                    close = v[4].jsonPrimitive.content.toBigDecimal(),
                    volume = v[5].jsonPrimitive.content.toBigDecimal(),
                    startTime = startTime,
                    endTime = startTime + durationMs,
                )
            }.sortedBy { it.startTime }
    }

    private fun windowToInterval(window: TimeWindow): String =
        when (window.durationMs) {
            60_000L -> "1"
            180_000L -> "3"
            300_000L -> "5"
            900_000L -> "15"
            1_800_000L -> "30"
            3_600_000L -> "60"
            7_200_000L -> "120"
            14_400_000L -> "240"
            86_400_000L -> "D"
            604_800_000L -> "W"
            else -> error("Unsupported Bybit kline interval: ${window.durationMs}ms")
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.bybit.com"
    }
}
