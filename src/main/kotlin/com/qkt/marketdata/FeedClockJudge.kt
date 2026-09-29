package com.qkt.marketdata

import com.qkt.common.Clock
import org.slf4j.Logger

/**
 * The broker-clock half of [FeedHealthJudge]: a tick timestamp further than the tolerance from
 * the local clock is either the venue's last print before a gap (closed venue, or older than any
 * server-zone offset), late delivery (a sub-hour lag), or a mis-set `server_time_zone`. Each is
 * said once per transition; the first fresh tick in tolerance clears it.
 */
internal class FeedClockJudge(
    private val clock: Clock,
    private val maxClockSkewMs: Long,
    private val inSession: (symbol: String, nowMs: Long) -> Boolean,
    private val log: Logger,
    private val raise: (symbol: String, state: SymbolFeedState, reason: String, fault: FeedFault) -> Unit,
) {
    /** True when [state]'s broker clock is out of tolerance, so new orders must wait; alerts once. */
    fun outOfTolerance(
        symbol: String,
        state: SymbolFeedState,
    ): Boolean {
        if (kotlin.math.abs(state.lastSkewMs) <= maxClockSkewMs) return false
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
            return true
        }
        if (!state.skewAlerted) {
            state.skewAlerted = true
            // A sub-hour lag behind the clock is late delivery, not a mis-set zone: the
            // smallest real zone error is a whole hour. Same suppression, different knob.
            val lag = state.lastSkewMs < 0L && -state.lastSkewMs < MarketDataGate.MIN_ZONE_OFFSET_MS
            val reason =
                if (lag) {
                    "broker ticks trail the local clock by ${-state.lastSkewMs}ms, beyond the ${maxClockSkewMs}ms tolerance"
                } else {
                    "broker tick clock skew ${state.lastSkewMs}ms exceeds ${maxClockSkewMs}ms"
                }
            if (lag) {
                log.error(
                    "market data for {} LAGGING: broker ticks trail the local clock by {}ms, beyond the {}ms " +
                        "tolerance — suppressing new orders (check gateway/venue feed latency)",
                    symbol,
                    -state.lastSkewMs,
                    maxClockSkewMs,
                )
            } else {
                log.error(
                    "market data for {} CLOCK-SKEWED: broker tick time {}ms from local clock " +
                        "exceeds {}ms tolerance — suppressing new orders (check server_time_zone)",
                    symbol,
                    state.lastSkewMs,
                    maxClockSkewMs,
                )
            }
            raise(symbol, state, reason, FeedFault.CLOCK_SKEW)
        }
        return true
    }
}
