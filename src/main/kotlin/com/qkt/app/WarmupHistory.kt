package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import java.time.Instant

private const val MAX_WARMUP_RANGE_ATTEMPTS = 8

/** Reuses exact per-session warmup requests across hub seeding and synthetic tick replay. */
internal class WarmupHistoryLoader(
    private val source: MarketSource,
) {
    private val cache = mutableMapOf<Request, List<Candle>>()

    fun load(
        symbol: String,
        window: TimeWindow,
        count: Int,
        upperMs: Long,
    ): List<Candle> =
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
 * holiday, or venue outage. The bounded expansion keeps startup deterministic while
 * refusing to leave indicators silently underfilled.
 */
private fun loadWarmupBars(
    source: MarketSource,
    symbol: String,
    window: TimeWindow,
    count: Int,
    upperMs: Long,
): List<Candle> {
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

        if (available.size >= count) return available.takeLast(count)
        if (attempt < MAX_WARMUP_RANGE_ATTEMPTS - 1) {
            durationMs = Math.multiplyExact(durationMs, 2L)
        }
    }

    throw WarmupUnderfilledException(
        symbol = symbol,
        window = window,
        requested = count,
        available = available.size,
        upperMs = upperMs,
        searchedDurationMs = durationMs,
    )
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
