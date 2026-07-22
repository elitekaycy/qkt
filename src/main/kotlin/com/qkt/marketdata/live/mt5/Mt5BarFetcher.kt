package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import java.time.Instant
import okhttp3.OkHttpClient

/**
 * Fetches historical bars from the `mt5-gateway` `/fetch_data_range` endpoint.
 *
 * Translates qkt [TimeWindow] (e.g. 5m → "M5") and [TimeRange] (Instant from/to) into
 * the wire format the gateway expects (naive ISO without zone designator). Bar responses
 * are normalized from broker wall time to UTC using [serverTimeZone].
 *
 * The gateway rejects a single `fetch_data_range` spanning more than 31 days, but warmup
 * on higher timeframes needs longer history (e.g. 4h × 250 bars ≈ 42 days). The range is
 * therefore fetched in [MAX_CHUNK_DAYS]-day windows and concatenated; each chunk is
 * filtered to its own half-open window so a bar on a chunk boundary is not double-counted.
 */
class Mt5BarFetcher(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val serverTimeZone: MT5ServerTimeZone = MT5ServerTimeZone.UTC,
    private val normalizeBidBarsToMid: Boolean = false,
    private val apiKey: String? = null,
) {
    private val pointBySymbol = java.util.concurrent.ConcurrentHashMap<String, java.math.BigDecimal>()

    fun fetchRange(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val tf = windowToTimeframe(window)
        val client = Mt5DataClient(baseUrl, http, serverTimeZone, apiKey)
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
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        return chunkBoundaries(fromMs, toMs)
            .asSequence()
            .flatMap { (chunkFromMs, chunkToMs) ->
                val startIso = serverTimeZone.toServerLocal(Instant.ofEpochMilli(chunkFromMs)).toString()
                val endIso = serverTimeZone.toServerLocal(Instant.ofEpochMilli(chunkToMs)).toString()
                client
                    .fetchBarsByRange(symbol, tf, startIso, endIso, midPoint)
                    .asSequence()
                    // The gateway includes the currently-open bar when `end` lands inside it;
                    // clamp to this chunk's half-open window so consumers never see an unclosed
                    // bar and adjacent chunks don't both emit the boundary bar.
                    .filter { it.startTime in chunkFromMs until chunkToMs }
            }
    }

    /** Contiguous half-open [from, to) sub-ranges of at most [MAX_CHUNK_DAYS] each. */
    private fun chunkBoundaries(
        fromMs: Long,
        toMs: Long,
    ): List<Pair<Long, Long>> =
        buildList {
            val chunkMs = MAX_CHUNK_DAYS * 86_400_000L
            var start = fromMs
            while (start < toMs) {
                val end = minOf(start + chunkMs, toMs)
                add(start to end)
                start = end
            }
        }

    private fun windowToTimeframe(window: TimeWindow): String =
        when (window.durationMs) {
            60_000L -> "M1"
            300_000L -> "M5"
            900_000L -> "M15"
            1_800_000L -> "M30"
            3_600_000L -> "H1"
            14_400_000L -> "H4"
            86_400_000L -> "D1"
            else -> error("Unsupported MT5 timeframe: ${window.durationMs}ms")
        }

    private companion object {
        /** Gateway rejects a `fetch_data_range` wider than 31 days; stay safely under. */
        const val MAX_CHUNK_DAYS = 30L
    }
}
