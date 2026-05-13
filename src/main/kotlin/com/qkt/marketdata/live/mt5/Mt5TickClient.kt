package com.qkt.marketdata.live.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class Mt5TickClient(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
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

    private fun fetchOnce(
        symbol: String,
        capturedAtMs: Long,
    ): Mt5Tick {
        val url = "$baseUrl/symbol_info_tick/$symbol"
        val req = Request.Builder().url(url).build()
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
        val brokerMs =
            obj["time_msc"]?.jsonPrimitive?.content?.toLong()
                ?: (obj["time"]!!.jsonPrimitive.content.toLong() * 1000L)
        return Mt5Tick(
            capturedAtMs = capturedAtMs,
            brokerTimeMs = brokerMs,
            bid = bid,
            ask = ask,
            last = last,
            flags = flags,
        )
    }
}
