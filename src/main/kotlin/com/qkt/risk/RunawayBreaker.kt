package com.qkt.risk

import com.qkt.common.Clock

/**
 * Circuit breaker for a strategy that is wrong at machine speed (FIA §1.5; Knight ran
 * 45 minutes with alerts firing and nothing blocking). Counts per-strategy closing
 * fills (round-trip proxy) and broker rejections in sliding windows; a breach halts
 * the strategy PERSISTENTLY — an operator must diagnose and `qkt resume`.
 *
 * Engine-thread only — callers record from bus subscribers, which the live loop
 * already serializes. A threshold of zero disables that counter.
 */
class RunawayBreaker(
    private val clock: Clock,
    private val riskState: RiskState,
    private val maxRoundTrips: Int = DEFAULT_MAX_ROUND_TRIPS,
    private val roundTripWindowMs: Long = DEFAULT_ROUND_TRIP_WINDOW_MS,
    private val maxRejections: Int = DEFAULT_MAX_REJECTIONS,
    private val rejectionWindowMs: Long = DEFAULT_REJECTION_WINDOW_MS,
) {
    private val closesByStrategy = mutableMapOf<String, ArrayDeque<Long>>()
    private val rejectionsByStrategy = mutableMapOf<String, ArrayDeque<Long>>()

    /** Record a closing fill (realized PnL != 0) for [strategyId]. */
    fun recordClose(strategyId: String) {
        if (maxRoundTrips <= 0 || strategyId.isBlank()) return
        val count = record(closesByStrategy, strategyId, roundTripWindowMs)
        if (count > maxRoundTrips) {
            riskState.haltStrategy(
                strategyId,
                "runaway breaker: $count round trips in ${roundTripWindowMs / 1000}s " +
                    "(max $maxRoundTrips) — fill/re-enter loop suspected",
                scope = HaltScope.PERSISTENT,
            )
        }
    }

    /** Record a broker rejection for [strategyId]. */
    fun recordRejection(strategyId: String) {
        if (maxRejections <= 0 || strategyId.isBlank()) return
        val count = record(rejectionsByStrategy, strategyId, rejectionWindowMs)
        if (count > maxRejections) {
            riskState.haltStrategy(
                strategyId,
                "runaway breaker: $count broker rejections in ${rejectionWindowMs / 1000}s " +
                    "(max $maxRejections) — strategy is hammering the venue",
                scope = HaltScope.PERSISTENT,
            )
        }
    }

    private fun record(
        map: MutableMap<String, ArrayDeque<Long>>,
        strategyId: String,
        windowMs: Long,
    ): Int {
        val now = clock.now()
        val stamps = map.getOrPut(strategyId) { ArrayDeque() }
        stamps.addLast(now)
        while (stamps.isNotEmpty() && now - stamps.first() > windowMs) stamps.removeFirst()
        return stamps.size
    }

    companion object {
        /** Conservative live defaults: 10 round trips in 10 minutes, 5 rejections in 1 minute. */
        const val DEFAULT_MAX_ROUND_TRIPS: Int = 10
        const val DEFAULT_ROUND_TRIP_WINDOW_MS: Long = 10L * 60_000L
        const val DEFAULT_MAX_REJECTIONS: Int = 5
        const val DEFAULT_REJECTION_WINDOW_MS: Long = 60_000L
    }
}
