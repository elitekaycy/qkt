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
        // MT5 aggregates multi-hour bars (H4, D1) on the broker's own day boundary, so a
        // New York-close broker's H4 bars start at 01:00/05:00/... UTC — off the epoch grid
        // every other part of qkt (live tick aggregation, backtests) uses. Fetch H1 instead
        // (hour bars stay hour-aligned for whole-hour server offsets) and rebuild the target
        // bars on the epoch grid, e.g. broker H4 09:00+13:00 server → qkt 08:00–12:00 UTC.
        if (window.durationMs > HOUR_MS) {
            require(window.durationMs % HOUR_MS == 0L) {
                "Cannot align ${window.durationMs}ms bars to the UTC grid from MT5 history"
            }
            val alignedFrom = Instant.ofEpochMilli((range.from.toEpochMilli() / window.durationMs) * window.durationMs)
            val hourly = fetchRangeRaw(symbol, TimeWindow.parse("1h"), TimeRange(alignedFrom, range.to))
            return aggregateToGrid(hourly, window, range.to.toEpochMilli())
        }
        return fetchRangeRaw(symbol, window, range)
    }

    private fun fetchRangeRaw(
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
                    .onEach { bar ->
                        if (window.durationMs == HOUR_MS) {
                            checkHourAligned(bar)
                        }
                    }
                    // The gateway includes the currently-open bar when `end` lands inside it.
                    // Require the complete candle to fit in this chunk so a mid-bar upper bound
                    // cannot leak look-ahead data and adjacent chunks cannot duplicate a bar.
                    .filter {
                        it.startTime >= chunkFromMs &&
                            it.endTime <= chunkToMs
                    }
            }
    }

    /**
     * Rebuilds [window]-sized bars on the epoch-aligned UTC grid from hourly bars.
     *
     * A bucket is emitted when it holds at least one hourly bar and closes at or before
     * [upperMs] — partial-coverage buckets are legitimate (a week-open 4h bucket only has
     * the hours the venue traded, exactly like a bar built live from ticks).
     */
    private fun aggregateToGrid(
        hourly: Sequence<Candle>,
        window: TimeWindow,
        upperMs: Long,
    ): Sequence<Candle> {
        val durationMs = window.durationMs
        val buckets = linkedMapOf<Long, MutableList<Candle>>()
        for (bar in hourly.sortedBy { it.startTime }) {
            checkHourAligned(bar)
            buckets.getOrPut((bar.startTime / durationMs) * durationMs) { mutableListOf() }.add(bar)
        }
        return buckets
            .asSequence()
            .filter { (start, _) -> start + durationMs <= upperMs }
            .map { (start, bars) ->
                Candle(
                    symbol = bars.first().symbol,
                    open = bars.first().open,
                    high = bars.maxOf { it.high },
                    low = bars.minOf { it.low },
                    close = bars.last().close,
                    volume = bars.sumOf { it.volume },
                    startTime = start,
                    endTime = start + durationMs,
                    bid = bars.last().bid,
                    ask = bars.last().ask,
                )
            }
    }

    private fun checkHourAligned(bar: Candle) {
        check(bar.startTime % HOUR_MS == 0L) {
            "MT5 H1 bar at ${Instant.ofEpochMilli(bar.startTime)} is not hour-aligned after " +
                "$serverTimeZone conversion; cannot rebuild the UTC grid (non-whole-hour server offset?)"
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
            else -> error("Unsupported MT5 timeframe: ${window.durationMs}ms")
        }

    private companion object {
        /** Gateway rejects a `fetch_data_range` wider than 31 days; stay safely under. */
        const val MAX_CHUNK_DAYS = 30L
        const val HOUR_MS = 3_600_000L
    }
}
