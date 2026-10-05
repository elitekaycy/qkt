package com.qkt.marketdata.store.binance

import com.qkt.marketdata.openinterest.OpenInterest
import com.qkt.marketdata.openinterest.OpenInterestSource
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Binance USDⓈ-M contracts' open interest from the public futures API (`/futures/data/openInterestHist`, no
 * key), at its finest period, five minutes. `sumOpenInterest` is in the base asset, a USDⓈ-M contract's order
 * quantity. Each figure is stamped with the start of its period and published after it (about 95 s after the
 * stamp, measured 2026-10-04), so it is stored as known at the period's end. Binance keeps only the last 30
 * days: an older `--from` is refused naming the limit. Asked for more than it returns at once, Binance answers
 * the newest of the range, so the range is read in spans of [PAGE] periods. `BINANCE_UM:BTCUSDT` is `BTCUSDT`.
 */
class BinanceOpenInterest(
    private val apiBaseUrl: String = BinanceVisionClient.API_BASE,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : OpenInterestSource {
    override fun figures(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<OpenInterest> {
        val oldest = clock() - KEPT_MS
        require(fromMs - PERIOD_MS >= oldest) {
            "Binance keeps only the last 30 days of open interest; start at ${java.time.Instant.ofEpochMilli(
                oldest + PERIOD_MS,
            )} or later"
        }
        val symbol = qktSymbol.substringAfter(':')
        val figures = ArrayList<OpenInterest>()
        var start = fromMs - PERIOD_MS
        while (start <= toMs - PERIOD_MS) {
            val end = minOf(toMs - PERIOD_MS, start + PAGE * PERIOD_MS - 1)
            figures += page(symbol, start, end)
            start = end + 1
        }
        return figures.filter { it.timeMs in fromMs..toMs }
    }

    private fun page(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<OpenInterest> {
        val url =
            "$apiBaseUrl/futures/data/openInterestHist?symbol=$symbol&period=5m&startTime=$fromMs&endTime=$toMs&limit=$PAGE"
        val body =
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) {
                    "HTTP ${resp.code} reading Binance open interest of $symbol: ${resp.body?.string()?.take(200)}"
                }
                resp.body?.string() ?: error("empty open-interest body for $symbol")
            }
        return parse(body)
    }

    companion object {
        private const val PAGE = 500
        private const val PERIOD_MS = 300_000L
        private const val KEPT_MS = 30 * 86_400_000L

        /** One `/futures/data/openInterestHist` answer, each figure from its exact text, known at its period's end. */
        fun parse(body: String): List<OpenInterest> =
            Json.parseToJsonElement(body).jsonArray.map { row ->
                val o = row.jsonObject
                val text = { key: String -> (o.getValue(key) as JsonPrimitive).content }
                OpenInterest(text("timestamp").toLong() + PERIOD_MS, BigDecimal(text("sumOpenInterest")))
            }
    }
}
