package com.qkt.marketdata.live.mt5

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import okhttp3.OkHttpClient

/**
 * Fetches historical bars from the `mt5-gateway` `/fetch_data_range` endpoint.
 *
 * Translates qkt [TimeWindow] (e.g. 5m → "M5") and [TimeRange] (Instant from/to) into
 * the wire format the gateway expects (naive ISO without zone designator — the gateway
 * interprets in broker-local time).
 */
class Mt5BarFetcher(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    fun fetchRange(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val tf = windowToTimeframe(window)
        val startIso = range.from.toString().removeSuffix("Z")
        val endIso = range.to.toString().removeSuffix("Z")
        val client = Mt5DataClient(baseUrl, http)
        return client.fetchBarsByRange(symbol, tf, startIso, endIso).asSequence()
    }

    private fun windowToTimeframe(window: TimeWindow): String =
        when (window.durationMs) {
            60_000L -> "M1"
            300_000L -> "M5"
            900_000L -> "M15"
            3_600_000L -> "H1"
            14_400_000L -> "H4"
            86_400_000L -> "D1"
            else -> error("Unsupported MT5 timeframe: ${window.durationMs}ms")
        }
}
