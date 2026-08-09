package com.qkt.risk

import com.qkt.common.Clock

/** The safety rule that caused a [RunawayBreakerTrip]. */
enum class RunawayBreakerRule {
    ROUND_TRIPS,
    BROKER_REJECTIONS,
}

/** Structured evidence that a strategy crossed one live runaway-breaker threshold. */
data class RunawayBreakerTrip(
    val timestampMs: Long,
    val strategyId: String,
    val rule: RunawayBreakerRule,
    val count: Int,
    val threshold: Int,
    val windowMs: Long,
) {
    /** Operator-facing halt/warning text shared by live enforcement and replay disclosure. */
    fun reason(): String =
        when (rule) {
            RunawayBreakerRule.ROUND_TRIPS ->
                "runaway breaker: $count round trips in ${windowMs / 1000}s " +
                    "(max $threshold) - fill/re-enter loop suspected"
            RunawayBreakerRule.BROKER_REJECTIONS ->
                "runaway breaker: $count broker rejections in ${windowMs / 1000}s " +
                    "(max $threshold) - strategy is hammering the venue"
        }
}

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
    private val enforce: Boolean = true,
    private val onTrip: (RunawayBreakerTrip) -> Unit = {},
) {
    private val closesByStrategy = mutableMapOf<String, ArrayDeque<Long>>()
    private val rejectionsByStrategy = mutableMapOf<String, ArrayDeque<Long>>()
    private val activeRoundTripStrategies = mutableSetOf<String>()
    private val activeRejectionStrategies = mutableSetOf<String>()

    /** Record a closing fill (realized PnL != 0) for [strategyId]. */
    fun recordClose(strategyId: String) {
        if (maxRoundTrips <= 0 || strategyId.isBlank()) return
        evaluate(
            strategyId = strategyId,
            rule = RunawayBreakerRule.ROUND_TRIPS,
            map = closesByStrategy,
            threshold = maxRoundTrips,
            windowMs = roundTripWindowMs,
            activeStrategies = activeRoundTripStrategies,
        )
    }

    /** Record a broker rejection for [strategyId]. */
    fun recordRejection(strategyId: String) {
        if (maxRejections <= 0 || strategyId.isBlank()) return
        evaluate(
            strategyId = strategyId,
            rule = RunawayBreakerRule.BROKER_REJECTIONS,
            map = rejectionsByStrategy,
            threshold = maxRejections,
            windowMs = rejectionWindowMs,
            activeStrategies = activeRejectionStrategies,
        )
    }

    private fun evaluate(
        strategyId: String,
        rule: RunawayBreakerRule,
        map: MutableMap<String, ArrayDeque<Long>>,
        threshold: Int,
        windowMs: Long,
        activeStrategies: MutableSet<String>,
    ) {
        val now = clock.now()
        val count = record(map, strategyId, windowMs, now)
        if (count <= threshold) {
            activeStrategies.remove(strategyId)
            return
        }
        if (!activeStrategies.add(strategyId)) return
        val trip =
            RunawayBreakerTrip(
                timestampMs = now,
                strategyId = strategyId,
                rule = rule,
                count = count,
                threshold = threshold,
                windowMs = windowMs,
            )
        onTrip(trip)
        if (enforce) {
            riskState.haltStrategy(
                strategyId,
                trip.reason(),
                scope = HaltScope.PERSISTENT,
            )
        }
    }

    private fun record(
        map: MutableMap<String, ArrayDeque<Long>>,
        strategyId: String,
        windowMs: Long,
        now: Long,
    ): Int {
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
