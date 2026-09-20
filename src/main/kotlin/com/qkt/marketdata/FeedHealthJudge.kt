package com.qkt.marketdata

import com.qkt.common.Clock
import org.slf4j.Logger

/**
 * Health verdict half of [MarketDataGate]: decides whether a symbol's feed is fit for NEW
 * orders (outlier run, clock skew versus venue-closed last print, scheduled pause, stale
 * quotes), raises each alert once per unhealthy transition, and clears the latches when a
 * fresh tick recovers the feed. Logs under the gate's logger so operator log lines are
 * unchanged.
 */
internal class FeedHealthJudge(
    private val clock: Clock,
    private val staleAgeMultiple: Double,
    private val minStaleAgeMs: Long,
    private val maxClockSkewMs: Long,
    private val onUnhealthy: (symbol: String, reason: String) -> Unit,
    private val inSession: (symbol: String, nowMs: Long) -> Boolean,
    private val scheduledBreak: (symbol: String, nowMs: Long) -> Boolean,
    private val log: Logger,
) {
    /** Clears the pause, stale, closed and skew latches after an accepted tick for [symbol]. */
    fun onFreshTick(
        symbol: String,
        state: SymbolFeedState,
    ) {
        if (state.pausedAlerted) {
            state.pausedAlerted = false
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
                onUnhealthy(symbol, "${state.rejectedOutlierRun} consecutive outlier tick(s) rejected")
            }
            return false
        }
        if (kotlin.math.abs(state.lastSkewMs) > maxClockSkewMs) {
            // A print that trails the local clock by more than any plausible server-zone
            // offset, or while the venue is closed, is the venue's last tick before a
            // gap — a weekend, a holiday, a symbol that opens later than its peers. Not
            // a clock problem: keep new orders suppressed (nothing to trade against),
            // say so once at INFO, and let the first fresh tick clear it (#1056).
            val now = clock.now()
            val lastPrint =
                state.lastSkewMs < 0L &&
                    (-state.lastSkewMs > MarketDataGate.MAX_PLAUSIBLE_ZONE_OFFSET_MS || !inSession(symbol, now))
            if (lastPrint) {
                if (!state.closedAlerted) {
                    state.closedAlerted = true
                    log.info(
                        "market data for {}: venue closed — last print {}ms old; new orders wait for a fresh tick",
                        symbol,
                        -state.lastSkewMs,
                    )
                }
                return false
            }
            if (!state.skewAlerted) {
                state.skewAlerted = true
                log.error(
                    "market data for {} CLOCK-SKEWED: broker tick time {}ms from local clock " +
                        "exceeds {}ms tolerance — suppressing new orders (check server_time_zone)",
                    symbol,
                    state.lastSkewMs,
                    maxClockSkewMs,
                )
                onUnhealthy(symbol, "broker tick clock skew ${state.lastSkewMs}ms exceeds ${maxClockSkewMs}ms")
            }
            return false
        }
        val threshold = staleThresholdMs(state)
        val now = clock.now()
        val age = now - state.lastSeenMs
        val healthy = age <= threshold
        if (!healthy && !state.staleAlerted && scheduledBreak(symbol, now)) {
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
        if (!healthy && !state.staleAlerted) {
            // A gap that outlives its scheduled break is a feed fault after all.
            state.staleAlerted = true
            log.error(
                "market data for {} STALE: age {}ms exceeds threshold {}ms — suppressing new orders",
                symbol,
                age,
                threshold,
            )
            onUnhealthy(symbol, "quote age ${age}ms exceeds ${threshold}ms threshold")
        }
        return healthy
    }

    /** Quote age past which [state]'s symbol is stale: its smoothed gap times the multiple, floored. */
    fun staleThresholdMs(state: SymbolFeedState): Long {
        val fromGap = (state.ewmaGapMs * staleAgeMultiple).toLong()
        return maxOf(fromGap, minStaleAgeMs)
    }
}
