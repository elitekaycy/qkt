package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.broker.mt5.mt5RequestBuilder
import com.qkt.broker.mt5.unwrapMT5Data
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class Mt5DataClient(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val serverTimeZone: MT5ServerTimeZone = MT5ServerTimeZone.UTC,
    private val apiKey: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchBarsByPos(
        symbol: String,
        timeframe: String,
        numBars: Int,
    ): List<Candle> {
        val url = "$baseUrl/fetch_data_pos?symbol=$symbol&timeframe=$timeframe&num_bars=$numBars"
        return fetchAndParse(url, symbol, timeframe)
    }

    fun fetchBarsByRange(
        symbol: String,
        timeframe: String,
        startIso: String,
        endIso: String,
        midPoint: BigDecimal? = null,
    ): List<Candle> {
        val s = java.net.URLEncoder.encode(startIso, "UTF-8")
        val e = java.net.URLEncoder.encode(endIso, "UTF-8")
        val url = "$baseUrl/fetch_data_range?symbol=$symbol&timeframe=$timeframe&start=$s&end=$e"
        return fetchAndParse(url, symbol, timeframe, midPoint)
    }

    fun fetchSymbolPoint(symbol: String): BigDecimal? {
        val req = mt5RequestBuilder("$baseUrl/symbol_info/$symbol", apiKey).build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val raw = resp.body?.string() ?: return null
            json
                .parseToJsonElement(raw)
                .jsonObject["point"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toBigDecimalOrNull()
        }
    }

    private fun fetchAndParse(
        url: String,
        symbol: String,
        timeframe: String,
        midPoint: BigDecimal? = null,
    ): List<Candle> {
        val req = mt5RequestBuilder(url, apiKey).build()
        val raw =
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "MT5 gateway HTTP ${resp.code} for $url: ${resp.body?.string()}" }
                resp.body?.string() ?: error("MT5 gateway empty body for $url")
            }
        val parsed = unwrapMT5Data(json.parseToJsonElement(raw))
        val rows: JsonArray =
            when {
                parsed is JsonArray -> parsed
                parsed is JsonObject && parsed["bars"] is JsonArray -> parsed["bars"] as JsonArray
                parsed is JsonObject && parsed["data"] is JsonArray -> parsed["data"] as JsonArray
                else -> error("Unexpected MT5 response shape for $url; raw=$raw")
            }
        val tfMs = timeframeToMillis(timeframe)
        return rows.map { row -> rowToCandle(symbol, row.jsonObject, tfMs, midPoint) }
    }

    private fun rowToCandle(
        symbol: String,
        row: JsonObject,
        tfMs: Long,
        midPoint: BigDecimal?,
    ): Candle {
        val halfSpread =
            midPoint?.let { point ->
                val spread =
                    row["spread"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                        ?: error("MT5 bar row missing spread required for bid-to-mid normalization: $row")
                spread.multiply(point).divide(BigDecimal(2))
            } ?: BigDecimal.ZERO
        val open =
            row["open"]!!
                .jsonPrimitive.content
                .toBigDecimal()
                .add(halfSpread)
        val high =
            row["high"]!!
                .jsonPrimitive.content
                .toBigDecimal()
                .add(halfSpread)
        val low =
            row["low"]!!
                .jsonPrimitive.content
                .toBigDecimal()
                .add(halfSpread)
        val close =
            row["close"]!!
                .jsonPrimitive.content
                .toBigDecimal()
                .add(halfSpread)
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
            val epochMs = if (epoch > 100_000_000_000L) epoch else epoch * 1000L
            return serverTimeZone.serverEpochToUtc(epochMs)
        }
        val wallTime =
            runCatching {
                LocalDateTime.ofInstant(Instant.parse(rawTime), java.time.ZoneOffset.UTC)
            }.getOrElse {
                val s = rawTime.replace('T', ' ').substringBefore('.')
                LocalDateTime.parse(s, NAIVE_FORMAT)
            }
        return serverTimeZone.toUtc(wallTime).toEpochMilli()
    }

    private fun timeframeToMillis(tf: String): Long =
        when (tf.uppercase()) {
            "M1" -> 60_000L
            "M5" -> 300_000L
            "M15" -> 900_000L
            "M30" -> 1_800_000L
            "H1" -> 3_600_000L
            "H4" -> 14_400_000L
            "D1" -> 86_400_000L
            else -> error("Unknown MT5 timeframe: $tf")
        }

    companion object {
        private val NAIVE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
