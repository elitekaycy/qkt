package com.qkt.app

import com.qkt.candles.CandleAggregator
import com.qkt.dsl.compile.CandleHub

/**
 * Closes ended candle windows by time rather than by the next tick, for the primary-window
 * aggregator and every candle-hub slot. Live closes from the wall-clock heartbeat; replay closes
 * from event time at the heartbeat step live would have used, so both close a quiet symbol's bar
 * on the same step (#1134, #1138).
 */
internal class CandleWindowCloser(
    /** The primary-window aggregator (candle events on the bus); null when no window configured. */
    private val windowAggregator: CandleAggregator?,
    private val candleHub: CandleHub,
    /**
     * Replay's stand-in for the live heartbeat (#1138): a quiet symbol's ended bar closes on
     * the first replayed tick at or past the first [replayHeartbeatIntervalMs] step that is at
     * least [replayCandleCloseGraceMs] after the window end. Live closes it from the wall clock
     * with the same grace, so both decide on the same heartbeat step.
     */
    private val replayCandleCloseGraceMs: Long,
    private val replayHeartbeatIntervalMs: Long,
) {
    /** Close every window ended at [nowMs]. */
    fun flushClosed(nowMs: Long) {
        windowAggregator?.flushClosed(nowMs)
        candleHub.flushClosed(nowMs)
    }

    /** Close every window live's heartbeat would have closed by replayed event time [eventMs]. */
    fun flushReplayAt(eventMs: Long) = flushClosed(replayCloseAt(eventMs))

    /**
     * Late ticks rejected after their candle was finalized, across the default window aggregator
     * AND every hub slot. The hub is where a DSL stream's bars are built, so counting only the
     * default aggregator under-reported a multi-stream strategy's drops to zero.
     */
    fun droppedLateTicks(): Long = (windowAggregator?.droppedLateTicks ?: 0L) + candleHub.droppedLateTicks()

    /**
     * The latest window end that live's heartbeat would have closed by event time [eventMs]:
     * the heartbeat step at or before [eventMs], minus the grace — and never [eventMs] itself.
     */
    private fun replayCloseAt(eventMs: Long): Long {
        val step = Math.floorDiv(eventMs, replayHeartbeatIntervalMs) * replayHeartbeatIntervalMs
        return minOf(step - replayCandleCloseGraceMs, eventMs - 1L)
    }
}
