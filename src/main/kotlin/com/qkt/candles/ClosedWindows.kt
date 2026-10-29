package com.qkt.candles

import com.qkt.marketdata.Tick
import org.slf4j.LoggerFactory

/**
 * Where each symbol's closed candle windows end, for one [CandleAggregator]: the windows it emitted
 * itself and those seeded from the venue's history, and the live ticks that arrived too late for them.
 * A window is never reopened: a tick stamped inside a closed one is refused, counted as covered when
 * history already holds it, or as dropped when the engine closed that bar before the tick arrived.
 * History also covers the bars warmup replayed ([warmed]): the newest one may still be open, waiting
 * for the first live tick past it to close it, but a live tick stamped inside it never joins it.
 */
internal class ClosedWindows {
    private val log = LoggerFactory.getLogger(CandleAggregator::class.java)
    private val lastClosedEnd = mutableMapOf<String, Long>()
    private val historyClosedEnd = mutableMapOf<String, Long>()

    /** Live ticks rejected after their candle was finalized; synthetic warmup reordering is excluded. */
    var droppedLateTicks: Long = 0
        private set

    /** Live ticks inside a window seeded from venue history or replayed by warmup: that bar already holds them. */
    var historyCoveredTicks: Long = 0
        private set

    /** The end of [symbol]'s newest closed window, or [Long.MIN_VALUE] before any. */
    fun lastEnd(symbol: String): Long = lastClosedEnd[symbol] ?: Long.MIN_VALUE

    /** The aggregator emitted [symbol]'s window ending at [endTime]. */
    fun emitted(
        symbol: String,
        endTime: Long,
    ) {
        lastClosedEnd[symbol] = endTime
    }

    /** [symbol]'s windows ending at or before [endTime] were seeded from the venue's history. */
    fun closedThrough(
        symbol: String,
        endTime: Long,
    ) {
        if (endTime > lastEnd(symbol)) lastClosedEnd[symbol] = endTime
        if (endTime > (historyClosedEnd[symbol] ?: Long.MIN_VALUE)) historyClosedEnd[symbol] = endTime
    }

    /**
     * Warmup replayed [tick], synthesized from a bar of [sourceTimeframeMs]: that bar's window is history,
     * whose ticks a venue may send again on subscribe (a cached quote snapshot, #1525).
     */
    fun warmed(
        tick: Tick,
        sourceTimeframeMs: Long,
    ) {
        val end = Math.floorDiv(tick.timestamp, sourceTimeframeMs) * sourceTimeframeMs + sourceTimeframeMs
        if (end > (historyClosedEnd[tick.symbol] ?: Long.MIN_VALUE)) historyClosedEnd[tick.symbol] = end
    }

    /** A tick that came after its window closed, or a live one inside history: counted (when [live]) and refused. */
    fun refuses(
        tick: Tick,
        live: Boolean,
    ): Boolean {
        val covered = live && tick.timestamp < (historyClosedEnd[tick.symbol] ?: Long.MIN_VALUE)
        if (!covered && tick.timestamp >= lastEnd(tick.symbol)) return false
        if (covered) {
            historyCoveredTicks++
        } else if (live) {
            droppedLateTicks++
            // A dropped late tick is a bar that silently disagrees with the venue's own, so
            // it must never be a counter nobody reads. Throttled: a feed stall drops a burst.
            if (droppedLateTicks == 1L || droppedLateTicks % LATE_DROP_LOG_EVERY == 0L) {
                log.warn(
                    "late tick for {} stamped {} arrived after its candle closed at {} — " +
                        "that bar under-reports the venue ({} dropped so far); raise " +
                        "candle_close_grace_ms if this persists",
                    tick.symbol,
                    tick.timestamp,
                    lastClosedEnd[tick.symbol],
                    droppedLateTicks,
                )
            }
        }
        return true
    }

    /** A live tick older than the window open for its symbol: dropped. */
    fun droppedBeforeOpen() {
        droppedLateTicks++
    }

    private companion object {
        /** Throttle for the late-drop warning: a stalled feed drops a burst, not one tick. */
        const val LATE_DROP_LOG_EVERY: Long = 100L
    }
}
