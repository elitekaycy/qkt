package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import java.time.Instant

private const val MAX_WARMUP_RANGE_ATTEMPTS = 8

private data class LoadedBars(
    val candles: List<Candle>,
    val searchedDurationMs: Long,
)

/** Reuses exact per-session warmup requests across hub seeding and synthetic tick replay. */
internal class WarmupHistoryLoader(
    private val source: MarketSource,
) {
    private val cache = mutableMapOf<Request, LoadedBars>()

    fun load(
        symbol: String,
        window: TimeWindow,
        count: Int,
        upperMs: Long,
    ): List<Candle> {
        val loaded = cached(symbol, window, count, upperMs)
        if (loaded.candles.size < count) {
            throw WarmupUnderfilledException(
                symbol = symbol,
                window = window,
                requested = count,
                available = loaded.candles.size,
                upperMs = upperMs,
                searchedDurationMs = loaded.searchedDurationMs,
            )
        }
        return loaded.candles
    }

    /** Returns all available closed history up to [count] without requiring a full window. */
    fun loadAvailable(
        symbol: String,
        window: TimeWindow,
        count: Int,
        upperMs: Long,
    ): List<Candle> = cached(symbol, window, count, upperMs).candles

    private fun cached(
        symbol: String,
        window: TimeWindow,
        count: Int,
        upperMs: Long,
    ): LoadedBars =
        cache.getOrPut(Request(symbol, window, count, upperMs)) {
            loadWarmupBars(source, symbol, window, count, upperMs)
        }

    private data class Request(
        val symbol: String,
        val window: TimeWindow,
        val count: Int,
        val upperMs: Long,
    )
}

/**
 * Loads the newest [count] closed bars, expanding across non-trading periods when needed.
 *
 * A wall-clock range of `count * window` is insufficient when it crosses a weekend,
 * holiday, or venue outage. The bounded expansion keeps startup deterministic; callers
 * choose whether an underfilled result aborts deployment or seeds best-effort replay history.
 */
private fun loadWarmupBars(
    source: MarketSource,
    symbol: String,
    window: TimeWindow,
    count: Int,
    upperMs: Long,
): LoadedBars {
    require(count > 0) { "warmup bar count must be > 0: $count" }

    val initialDurationMs = Math.multiplyExact(window.durationMs, count.toLong())
    var durationMs = initialDurationMs
    var available = emptyList<Candle>()

    repeat(MAX_WARMUP_RANGE_ATTEMPTS) { attempt ->
        val lowerMs = Math.subtractExact(upperMs, durationMs)
        val range = TimeRange(Instant.ofEpochMilli(lowerMs), Instant.ofEpochMilli(upperMs))
        available =
            source
                .bars(symbol, window, range)
                .filter { candle ->
                    candle.startTime >= lowerMs &&
                        candle.startTime < upperMs &&
                        candle.endTime <= upperMs
                }.distinctBy { it.startTime }
                .sortedBy { it.startTime }
                .toList()

        if (available.size >= count) {
            return LoadedBars(available.takeLast(count), durationMs)
        }
        if (attempt < MAX_WARMUP_RANGE_ATTEMPTS - 1) {
            durationMs = Math.multiplyExact(durationMs, 2L)
        }
    }

    return LoadedBars(available, durationMs)
}

/** Indicates that a live strategy could not obtain enough closed bars to initialize its indicators. */
class WarmupUnderfilledException(
    symbol: String,
    window: TimeWindow,
    requested: Int,
    available: Int,
    upperMs: Long,
    searchedDurationMs: Long,
) : RuntimeException(
        "qkt: insufficient warmup history for $symbol ${window.canonicalSpec()}: " +
            "requested=$requested available=$available after searching " +
            "${java.time.Duration.ofMillis(searchedDurationMs)} before ${Instant.ofEpochMilli(upperMs)}. " +
            "Deploy aborted so the strategy cannot run with unready indicators.",
    )
