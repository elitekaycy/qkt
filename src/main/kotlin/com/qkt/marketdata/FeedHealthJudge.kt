package com.qkt.marketdata

import com.qkt.common.Clock
import org.slf4j.Logger

/**
 * Health verdict half of [MarketDataGate]: decides whether a symbol's feed is fit for NEW
 * orders (outlier run, clock skew versus venue-closed last print, scheduled pause, venue out
 * of session, stale quotes), raises each fault alert once per unhealthy transition, clears
 * the latches when a fresh tick recovers the feed, and reports the end of an unhealthy episode
 * once. Logs under the gate's logger so operator log lines are unchanged.
 */
internal class FeedHealthJudge(
    private val clock: Clock,
    private val staleAgeMultiple: Double,
    private val minStaleAgeMs: Long,
    private val maxClockSkewMs: Long,
    private val onUnhealthy: (symbol: String, reason: String, fault: FeedFault) -> Unit,
    private val onRecovered: (symbol: String, reason: String, unhealthyForMs: Long) -> Unit,
    private val inSession: (symbol: String, nowMs: Long) -> Boolean,
    private val scheduledBreak: (symbol: String, nowMs: Long) -> Boolean,
    private val log: Logger,
) {
    private val clockJudge = FeedClockJudge(clock, maxClockSkewMs, inSession, log, ::raise)

    /**
     * Clears the pause, stale, closed and skew latches after an accepted tick for [symbol], then
     * closes the unhealthy episode if nothing is left wrong.
     */
    fun onFreshTick(
        symbol: String,
        state: SymbolFeedState,
    ) {
        if (state.pausedAlerted) {
            state.pausedAlerted = false
            state.pausedSeenMs = 0L
            // The pause gap would otherwise sit in the smoothed inter-tick gap for the next
            // hour and lift the stale threshold; restart the estimate from the live cadence.
            state.ewmaGapMs = 0.0
            log.info("market data for {} resumed after scheduled break", symbol)
        }
        if (state.staleAlerted) {
            state.staleAlerted = false
            log.info("market data for {} healthy again", symbol)
        }
        if (state.closedAlerted && kotlin.math.abs(state.lastSkewMs) <= maxClockSkewMs) {
            state.closedAlerted = false
            log.info("market data for {}: fresh print after venue gap; healthy again", symbol)
        }
        if (state.skewAlerted && kotlin.math.abs(state.lastSkewMs) <= maxClockSkewMs) {
            state.skewAlerted = false
            log.info(
                "market data for {} clock realigned: skew {}ms within {}ms tolerance",
                symbol,
                state.lastSkewMs,
                maxClockSkewMs,
            )
        }
        settleEpisode(symbol, state)
    }

    /**
     * Ends [symbol]'s open unhealthy episode, once, when no fault latch is left and the broker
     * clock is back in tolerance: a symbol that raised stale and clock skew recovers when both
     * have cleared, not at the first. A symbol that never raised a fault has nothing to end.
     */
    fun settleEpisode(
        symbol: String,
        state: SymbolFeedState,
    ) {
        val fault = state.unhealthyFault ?: return
        if (state.staleAlerted || state.skewAlerted || kotlin.math.abs(state.lastSkewMs) > maxClockSkewMs) return
        val unhealthyForMs = clock.now() - state.unhealthySinceMs
        state.unhealthyFault = null
        state.unhealthySinceMs = 0L
        log.info("market data for {} recovered after {}ms unhealthy ({})", symbol, unhealthyForMs, fault.wireName)
        onRecovered(symbol, "fresh tick after ${fault.wireName}", unhealthyForMs)
    }

    // Every fault alert goes through here so the episode opens at the first of them.
    private fun raise(
        symbol: String,
        state: SymbolFeedState,
        reason: String,
        fault: FeedFault,
    ) {
        if (state.unhealthyFault == null) {
            state.unhealthyFault = fault
            state.unhealthySinceMs = clock.now()
        }
        onUnhealthy(symbol, reason, fault)
    }

    /** See [MarketDataGate.isHealthy]; [state] is the observed state for [symbol]. */
    fun isHealthy(
        symbol: String,
        state: SymbolFeedState,
    ): Boolean {
        if (state.lastSeenMs == 0L) return true
        if (state.rejectedOutlierRun > 0) {
            if (!state.staleAlerted) {
                state.staleAlerted = true
                log.error(
                    "market data for {} UNHEALTHY: {} consecutive outlier tick(s) rejected — suppressing new orders",
                    symbol,
                    state.rejectedOutlierRun,
                )
                val reason = "${state.rejectedOutlierRun} consecutive outlier tick(s) rejected"
                raise(symbol, state, reason, FeedFault.OUTLIER)
            }
            return false
        }
        if (clockJudge.outOfTolerance(symbol, state)) return false
        val threshold = staleThresholdMs(state)
        val now = clock.now()
        val age = now - state.lastSeenMs
        val healthy = age <= threshold
        if (!healthy && !state.staleAlerted && scheduledBreak(symbol, now)) {
            state.pausedSeenMs = now
            if (!state.pausedAlerted) {
                state.pausedAlerted = true
                log.info(
                    "market data for {} PAUSED: scheduled break, quote age {}ms — suppressing new orders",
                    symbol,
                    age,
                )
            }
            return false
        }
        if (!healthy && !state.staleAlerted && !inSession(symbol, now)) {
            // Weekend, holiday, a symbol that opens later: the venue is shut, so a quote gap is
            // expected, not a feed fault. New orders still wait for the first fresh tick.
            if (!state.closedAlerted) {
                state.closedAlerted = true
                log.info(
                    "market data for {}: venue closed (out of session) — quote age {}ms; " +
                        "new orders wait for a fresh tick",
                    symbol,
                    age,
                )
            }
            return false
        }
        if (!healthy && !state.staleAlerted && state.pausedAlerted) {
            // The calendar says the break is over but the venue has not printed yet: its first
            // post-break tick trails the break end by seconds to minutes (#1279). The gap is
            // measured from the break end; only one that outlives the threshold is a fault.
            val sinceBreakEndMs = now - state.pausedSeenMs
            if (sinceBreakEndMs <= threshold) return false
            state.staleAlerted = true
            log.error(
                "market data for {} STALE: no quote within {}ms after the scheduled break ended " +
                    "(threshold {}ms) — suppressing new orders",
                symbol,
                sinceBreakEndMs,
                threshold,
            )
            val reason =
                "no quote within ${sinceBreakEndMs}ms after the scheduled break ended (threshold ${threshold}ms)"
            raise(symbol, state, reason, FeedFault.STALE)
            return false
        }
        if (!healthy && !state.staleAlerted) {
            state.staleAlerted = true
            log.error(
                "market data for {} STALE: age {}ms exceeds threshold {}ms — suppressing new orders",
                symbol,
                age,
                threshold,
            )
            raise(symbol, state, "quote age ${age}ms exceeds ${threshold}ms threshold", FeedFault.STALE)
        }
        return healthy
    }

    /** Quote age past which [state]'s symbol is stale: its smoothed gap times the multiple, floored. */
    fun staleThresholdMs(state: SymbolFeedState): Long {
        val fromGap = (state.ewmaGapMs * staleAgeMultiple).toLong()
        return maxOf(fromGap, minStaleAgeMs)
    }
}
