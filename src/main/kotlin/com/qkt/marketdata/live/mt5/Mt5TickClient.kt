package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.broker.mt5.mt5RequestBuilder
import com.qkt.broker.mt5.unwrapMT5Data
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class Mt5TickClient(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val serverTimeZone: MT5ServerTimeZone = MT5ServerTimeZone.UTC,
    private val apiKey: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Mt5Tick(
        val capturedAtMs: Long,
        val brokerTimeMs: Long,
        val bid: BigDecimal,
        val ask: BigDecimal,
        val last: BigDecimal,
        val flags: Int,
    ) {
        val mid: BigDecimal get() = bid.add(ask).divide(BigDecimal(2))
    }

    fun pollUntil(
        symbol: String,
        deadlineMs: Long,
        intervalMs: Long,
        sink: (Mt5Tick) -> Unit,
    ) {
        var lastBrokerMs = 0L
        while (System.currentTimeMillis() < deadlineMs) {
            val start = System.currentTimeMillis()
            val tick =
                runCatching { fetchOnce(symbol, capturedAtMs = start) }
                    .onFailure { e -> System.err.println("[mt5-poll] fetch failed: ${e.message}") }
                    .getOrNull()
            if (tick != null && tick.brokerTimeMs > lastBrokerMs) {
                lastBrokerMs = tick.brokerTimeMs
                sink(tick)
            }
            val elapsed = System.currentTimeMillis() - start
            val sleep = (intervalMs - elapsed).coerceAtLeast(0)
            if (sleep > 0) Thread.sleep(sleep)
        }
    }

    /**
     * Every tick the venue recorded in `(afterBrokerMs, toBrokerMs]`, oldest first.
     *
     * Unlike [fetchOnce], which returns only the quote current at the instant it is called, this
     * returns the whole interval — so the ticks delivered no longer depend on how fast the caller
     * polls. A tick arriving between two rounds falls inside the next window by construction.
     *
     * The gateway floors `from_date` to whole seconds and treats `to_date` as exclusive, so the
     * request is widened to the second containing [afterBrokerMs] and narrowed again by the
     * caller's `brokerTimeMs > afterBrokerMs` filter: the re-delivered head of that second is
     * dropped there, not here. A degenerate window yields an empty list without a request, since
     * the gateway rejects `from_date == to_date` with a validation error rather than empty data.
     */
    internal fun fetchRange(
        symbol: String,
        afterBrokerMs: Long,
        toBrokerMs: Long,
        capturedAtMs: Long,
    ): List<Mt5Tick> {
        val fromSec = Math.floorDiv(afterBrokerMs, MILLIS_PER_SECOND)
        val toSec = Math.floorDiv(toBrokerMs, MILLIS_PER_SECOND) + 1L
        if (toSec <= fromSec) return emptyList()
        val url =
            "$baseUrl/copy_ticks_range?symbol=$symbol" +
                "&from_date=${encode(serverStamp(fromSec * MILLIS_PER_SECOND))}" +
                "&to_date=${encode(serverStamp(toSec * MILLIS_PER_SECOND))}"
        val req = mt5RequestBuilder(url, apiKey).build()
        val raw =
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "MT5 gateway HTTP ${resp.code} for $url: ${resp.body?.string()}" }
                resp.body?.string() ?: error("MT5 gateway empty body for $url")
            }
        val rows =
            unwrapMT5Data(json.parseToJsonElement(raw)) as? JsonArray
                ?: error("MT5 ticks non-array for $symbol: $raw")
        return rows
            .map { element -> parseTick(element.jsonObject, capturedAtMs) }
            .sortedBy(Mt5Tick::brokerTimeMs)
    }

    private fun parseTick(
        obj: JsonObject,
        capturedAtMs: Long,
    ): Mt5Tick {
        val serverMs =
            obj["time_msc"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: ((obj["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * MILLIS_PER_SECOND)
        return Mt5Tick(
            capturedAtMs = capturedAtMs,
            brokerTimeMs = serverTimeZone.serverEpochToUtc(serverMs),
            bid = obj["bid"]!!.jsonPrimitive.content.toBigDecimal(),
            ask = obj["ask"]!!.jsonPrimitive.content.toBigDecimal(),
            last = obj["last"]?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO,
            flags = obj["flags"]?.jsonPrimitive?.content?.toInt() ?: 0,
        )
    }

    /**
     * A UTC instant rendered as the broker's own wall clock, truncated to the second.
     *
     * The gateway's date parameters are broker-local — [MT5ServerTimeZone.serverEpochToUtc] is
     * the inverse, applied to what comes back — so sending UTC to a `new_york_close` server
     * would shift the requested window by two or three hours.
     */
    private fun serverStamp(utcMs: Long): String =
        serverTimeZone
            .toServerLocal(Instant.ofEpochMilli(utcMs))
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    internal fun fetchOnce(
        symbol: String,
        capturedAtMs: Long,
    ): Mt5Tick {
        val url = "$baseUrl/symbol_info_tick/$symbol"
        val req = mt5RequestBuilder(url, apiKey).build()
        val raw =
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "MT5 gateway HTTP ${resp.code} for $url: ${resp.body?.string()}" }
                resp.body?.string() ?: error("MT5 gateway empty body for $url")
            }
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: error("MT5 tick non-object: $raw")
        val bid = obj["bid"]!!.jsonPrimitive.content.toBigDecimal()
        val ask = obj["ask"]!!.jsonPrimitive.content.toBigDecimal()
        val last = obj["last"]!!.jsonPrimitive.content.toBigDecimal()
        val flags = obj["flags"]!!.jsonPrimitive.content.toInt()
        val serverMs =
            obj["time_msc"]?.jsonPrimitive?.content?.toLong()
                ?: (obj["time"]!!.jsonPrimitive.content.toLong() * 1000L)
        val brokerMs = serverTimeZone.serverEpochToUtc(serverMs)
        return Mt5Tick(
            capturedAtMs = capturedAtMs,
            brokerTimeMs = brokerMs,
            bid = bid,
            ask = ask,
            last = last,
            flags = flags,
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND: Long = 1_000L
    }
}
