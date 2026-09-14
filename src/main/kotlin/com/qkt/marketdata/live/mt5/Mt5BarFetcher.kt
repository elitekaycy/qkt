package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
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
 *
 * MT5 serves only its native timeframes. Anything else is rebuilt on the UTC epoch grid from
 * the finest native source that fits: multi-hour windows from H1, non-native minute windows
 * (`EVERY 2m`, `7m`) from M1, and sub-minute windows (`EVERY 5s`) from `/copy_ticks_range`
 * through the same [CandleAggregator] the live feed uses, so a warmup bar equals the bar the
 * feed would have built from those ticks (#1133).
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
        val durationMs = window.durationMs
        if (durationMs < MINUTE_MS) {
            return aggregateTicks(symbol, window, range)
        }
        if (durationMs > HOUR_MS) {
            require(durationMs % HOUR_MS == 0L) {
                "Cannot align ${durationMs}ms bars to the UTC grid from MT5 history"
            }
            return aggregateFromNative(symbol, window, range, base = TimeWindow(HOUR_MS))
        }
        if (durationMs !in NATIVE_WINDOWS_MS) {
            require(durationMs % MINUTE_MS == 0L) {
                "Cannot align ${durationMs}ms bars to the UTC grid from MT5 history"
            }
            return aggregateFromNative(symbol, window, range, base = TimeWindow(MINUTE_MS))
        }
        return fetchRangeRaw(symbol, window, range)
    }

    /** Fetches the native [base] bars and rebuilds [window] bars on the epoch grid. */
    private fun aggregateFromNative(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
        base: TimeWindow,
    ): Sequence<Candle> {
        val alignedFrom = Instant.ofEpochMilli((range.from.toEpochMilli() / window.durationMs) * window.durationMs)
        val native = fetchRangeRaw(symbol, base, TimeRange(alignedFrom, range.to))
        return aggregateToGrid(native, window, range.to.toEpochMilli(), base)
    }

    /**
     * Sub-minute bars have no MT5 history; rebuild them from the venue's own tick record with
     * the live feed's aggregator. Only complete bars inside `[from, to)` are returned. The
     * range is bounded because ticks are voluminous: a 250-bar warmup on `EVERY 30s` is a
     * few minutes of ticks, but a day-long request would be a data export, not a warmup.
     */
    private fun aggregateTicks(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val fromMs = (range.from.toEpochMilli() / window.durationMs) * window.durationMs
        val toMs = range.to.toEpochMilli()
        if (toMs <= fromMs) return emptySequence()
        require(toMs - fromMs <= MAX_TICK_SPAN_MS) {
            "Sub-minute warmup for $symbol spans ${(toMs - fromMs) / 60_000L} minutes of ticks; " +
                "the limit is ${MAX_TICK_SPAN_MS / 60_000L} minutes. Reduce WARMUP or indicator periods on that stream."
        }
        val client = Mt5TickClient(baseUrl, http, serverTimeZone, apiKey)
        val bars = mutableListOf<Candle>()
        val aggregator = CandleAggregator.standalone(window) { bars.add(it) }
        var chunkFrom = fromMs
        while (chunkFrom < toMs) {
            val chunkTo = minOf(chunkFrom + TICK_CHUNK_MS, toMs)
            client
                .fetchRange(symbol, afterBrokerMs = chunkFrom - 1L, toBrokerMs = chunkTo, capturedAtMs = toMs)
                .asSequence()
                .filter { it.brokerTimeMs in fromMs until toMs }
                .filter { it.bid.signum() > 0 && it.ask.signum() > 0 }
                .forEach { tick ->
                    aggregator.onTick(
                        Tick(
                            symbol = symbol,
                            // Quote-driven instruments report last = 0: same mid fallback as the live feed.
                            price =
                                (if (tick.last.signum() > 0) tick.last else tick.mid)
                                    .setScale(Money.SCALE, Money.ROUNDING),
                            timestamp = tick.brokerTimeMs,
                            bid = tick.bid,
                            ask = tick.ask,
                        ),
                    )
                }
            chunkFrom = chunkTo
        }
        aggregator.flushClosed(toMs)
        return bars.asSequence().filter { it.startTime >= fromMs && it.endTime <= toMs }
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
     * Rebuilds [window]-sized bars on the epoch-aligned UTC grid from native [base] bars.
     *
     * A bucket is emitted when it holds at least one native bar and closes at or before
     * [upperMs] — partial-coverage buckets are legitimate (a week-open 4h bucket only has
     * the hours the venue traded, exactly like a bar built live from ticks).
     */
    private fun aggregateToGrid(
        native: Sequence<Candle>,
        window: TimeWindow,
        upperMs: Long,
        base: TimeWindow,
    ): Sequence<Candle> {
        val durationMs = window.durationMs
        val buckets = linkedMapOf<Long, MutableList<Candle>>()
        for (bar in native.sortedBy { it.startTime }) {
            checkAligned(bar, base)
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

    private fun checkHourAligned(bar: Candle) = checkAligned(bar, TimeWindow(HOUR_MS))

    private fun checkAligned(
        bar: Candle,
        base: TimeWindow,
    ) {
        check(bar.startTime % base.durationMs == 0L) {
            "MT5 ${if (base.durationMs == HOUR_MS) "H1" else "M1"} bar at ${Instant.ofEpochMilli(bar.startTime)} " +
                "is not ${if (base.durationMs == HOUR_MS) "hour" else "minute"}-aligned after " +
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
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L

        /** Windows `/fetch_data_range` serves directly; everything else is rebuilt on the grid. */
        val NATIVE_WINDOWS_MS = setOf(60_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L)

        /** Upper bound on one sub-minute warmup's tick span (6 hours). */
        const val MAX_TICK_SPAN_MS = 6L * HOUR_MS

        /** One `/copy_ticks_range` request per this much history, so a busy symbol stays in one page. */
        const val TICK_CHUNK_MS = 15L * MINUTE_MS
    }
}
