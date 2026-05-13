package com.qkt.marketdata.live.mt5

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class Mt5DataClient(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchBarsByPos(
        symbol: String,
        timeframe: String,
        numBars: Int,
    ): List<Candle> {
        val url = "$baseUrl/fetch_data_pos?symbol=$symbol&timeframe=$timeframe&num_bars=$numBars"
        val req = Request.Builder().url(url).build()
        val raw =
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "MT5 gateway HTTP ${resp.code} for $url: ${resp.body?.string()}" }
                resp.body?.string() ?: error("MT5 gateway empty body for $url")
            }
        val parsed = json.parseToJsonElement(raw)
        val rows: JsonArray =
            when {
                parsed is JsonArray -> parsed
                parsed is JsonObject && parsed["bars"] is JsonArray -> parsed["bars"] as JsonArray
                parsed is JsonObject && parsed["data"] is JsonArray -> parsed["data"] as JsonArray
                else -> error("Unexpected MT5 response shape for $url; raw=$raw")
            }
        val tfMs = timeframeToMillis(timeframe)
        return rows.map { row -> rowToCandle(symbol, row.jsonObject, tfMs) }
    }

    private fun rowToCandle(
        symbol: String,
        row: JsonObject,
        tfMs: Long,
    ): Candle {
        val open = row["open"]!!.jsonPrimitive.content.toBigDecimal()
        val high = row["high"]!!.jsonPrimitive.content.toBigDecimal()
        val low = row["low"]!!.jsonPrimitive.content.toBigDecimal()
        val close = row["close"]!!.jsonPrimitive.content.toBigDecimal()
        val volume =
            (row["tick_volume"] ?: row["volume"] ?: row["real_volume"])
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toBigDecimalOrNull()
                ?: BigDecimal.ZERO
        val startMs = parseStartTime(row)
        return Candle(
            symbol = symbol,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            startTime = startMs,
            endTime = startMs + tfMs,
        )
    }

    private fun parseStartTime(row: JsonObject): Long {
        val rawTime =
            row["time"]?.jsonPrimitive?.contentOrNull
                ?: row["timestamp"]?.jsonPrimitive?.contentOrNull
                ?: error("MT5 bar row missing time/timestamp: $row")
        rawTime.toLongOrNull()?.let { epoch ->
            return if (epoch > 100_000_000_000L) epoch else epoch * 1000L
        }
        runCatching { return Instant.parse(rawTime).toEpochMilli() }
        val s = rawTime.replace('T', ' ').substringBefore('.')
        val ldt = LocalDateTime.parse(s, NAIVE_FORMAT)
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private fun timeframeToMillis(tf: String): Long =
        when (tf.uppercase()) {
            "M1" -> 60_000L
            "M5" -> 300_000L
            "M15" -> 900_000L
            "H1" -> 3_600_000L
            "H4" -> 14_400_000L
            "D1" -> 86_400_000L
            else -> error("Unknown MT5 timeframe: $tf")
        }

    companion object {
        private val NAIVE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
