package com.qkt.marketdata.live.mt5

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import java.time.ZoneOffset
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
    private val serverTzOffsetHours: Int = 0,
    private val normalizeBidBarsToMid: Boolean = false,
) {
    private val pointBySymbol = java.util.concurrent.ConcurrentHashMap<String, java.math.BigDecimal>()

    fun fetchRange(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val tf = windowToTimeframe(window)
        val offset = ZoneOffset.ofHours(serverTzOffsetHours)
        val startIso =
            range.from
                .atOffset(offset)
                .toLocalDateTime()
                .toString()
        val endIso =
            range.to
                .atOffset(offset)
                .toLocalDateTime()
                .toString()
        val client = Mt5DataClient(baseUrl, http, serverTzOffsetHours)
        val midPoint =
            if (normalizeBidBarsToMid) {
                pointBySymbol[symbol]
                    ?: client.fetchSymbolPoint(symbol)?.also { pointBySymbol[symbol] = it }
                    ?: error(
                        "MT5 gateway did not provide point metadata for $symbol; cannot normalize warmup bars to mid",
                    )
            } else {
                null
            }
        // The gateway includes the currently-open bar when `end` lands inside it;
        // match Bybit / TradingView / Local boundary semantics so consumers never
        // see an unclosed bar.
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        return client
            .fetchBarsByRange(symbol, tf, startIso, endIso, midPoint)
            .asSequence()
            .filter { it.startTime in fromMs until toMs }
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
