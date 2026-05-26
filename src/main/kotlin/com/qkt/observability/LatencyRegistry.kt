package com.qkt.observability

import org.slf4j.LoggerFactory

/** Discrete pipeline stage measured by [LatencyRegistry]. */
enum class LatencyStage {
    /**
     * From the moment a strategy calls `emit(signal)` until `orderManager.submit(...)` returns.
     * Covers risk evaluation, order construction, and bus dispatch — the pipeline's own latency,
     * not the broker's.
     */
    SIGNAL_TO_SUBMISSION,

    /**
     * From `orderManager.submit(...)` until the matching `OrderFilled` event arrives. Dominated
     * by venue round-trip in live; near-zero in backtest with PaperBroker.
     */
    SUBMISSION_TO_FILL,
}

/**
 * Aggregates per-(strategy, stage) [LatencyTracker]s plus a per-order submit-time map for
 * the SIGNAL → FILL bridge. Every public mutating method is wrapped in `try { ... } catch
 * (Throwable)` so that an observability bug cannot leak into the trading hot path.
 *
 * Bounded memory: the per-orderId submit map is a [LinkedHashMap] with FIFO eviction once
 * [submitMapCap] entries are reached. Long-running daemons with orders that never fill
 * (cancellations, never-triggered limits) cannot leak memory through this registry.
 *
 * When [enabled] is `false`, every operation is a no-op short-circuit on the first line —
 * no allocations, no map lookups, no [System.nanoTime] calls. This is the path that runs
 * in production unless an operator sets `QKT_LATENCY_TRACKING=1`.
 */
class LatencyRegistry(
    val enabled: Boolean,
    strategyIds: List<String>,
    capacityPerTracker: Int = 1024,
    private val submitMapCap: Int = 10_000,
) {
    private val log = LoggerFactory.getLogger(LatencyRegistry::class.java)

    private val trackers: Map<String, Map<LatencyStage, LatencyTracker>> =
        if (enabled) {
            strategyIds.associateWith { _ ->
                LatencyStage.entries.associateWith { LatencyTracker(capacityPerTracker) }
            }
        } else {
            emptyMap()
        }

    private val submitNanos: MutableMap<String, Long> =
        if (enabled) {
            object : LinkedHashMap<String, Long>(64, 0.75f, false) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean = size > submitMapCap
            }
        } else {
            mutableMapOf()
        }

    /** Record an observation in nanoseconds for [strategyId] at [stage]. No-op when disabled. */
    fun observe(
        strategyId: String,
        stage: LatencyStage,
        nanos: Long,
    ) {
        if (!enabled) return
        try {
            trackers[strategyId]?.get(stage)?.observe(nanos)
        } catch (t: Throwable) {
            log.warn("LatencyRegistry.observe failed (strategyId=$strategyId stage=$stage)", t)
        }
    }

    /** Mark the moment an order was submitted to the broker. No-op when disabled. */
    fun recordSubmit(orderId: String) {
        if (!enabled) return
        try {
            synchronized(submitNanos) {
                submitNanos[orderId] = System.nanoTime()
            }
        } catch (t: Throwable) {
            log.warn("LatencyRegistry.recordSubmit failed (orderId=$orderId)", t)
        }
    }

    /**
     * Record the SUBMISSION→FILL latency for [orderId], attributed to [strategyId]. The
     * submit-time entry is consumed (removed) on success. No-op when disabled or when no
     * submit-time was previously recorded for [orderId] (e.g. a fill arrived without a
     * tracked submit because the map evicted it).
     */
    fun observeFill(
        orderId: String,
        strategyId: String,
    ) {
        if (!enabled) return
        try {
            val t0 = synchronized(submitNanos) { submitNanos.remove(orderId) } ?: return
            trackers[strategyId]?.get(LatencyStage.SUBMISSION_TO_FILL)?.observe(System.nanoTime() - t0)
        } catch (t: Throwable) {
            log.warn("LatencyRegistry.observeFill failed (orderId=$orderId strategyId=$strategyId)", t)
        }
    }

    /** Number of orderIds currently awaiting a fill (for tests). */
    fun pendingSubmits(): Int = synchronized(submitNanos) { submitNanos.size }

    /** Point-in-time snapshot for the `/latency` HTTP endpoint and `qkt status --latency`. */
    fun snapshot(): Report {
        if (!enabled) return Report(enabled = false, strategies = emptyMap())
        val out = LinkedHashMap<String, Map<LatencyStage, LatencyTracker.Snapshot>>()
        for ((strategyId, byStage) in trackers) {
            val perStage = LinkedHashMap<LatencyStage, LatencyTracker.Snapshot>()
            for ((stage, tracker) in byStage) {
                perStage[stage] = tracker.snapshot()
            }
            out[strategyId] = perStage
        }
        return Report(enabled = true, strategies = out)
    }

    data class Report(
        val enabled: Boolean,
        val strategies: Map<String, Map<LatencyStage, LatencyTracker.Snapshot>>,
    )
}
