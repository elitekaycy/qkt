package com.qkt.observe.insights

/**
 * Insights translation for session lifecycle: strategy start and stop, the deployed
 * strategy roster, and durable-state persistence health. Mixed into [InsightsTranslate].
 */
interface SessionInsights {
    fun strategyStarted(
        strategyId: String,
        ts: Long,
        metadata: Map<String, Any?> = emptyMap(),
    ): InsightsEnvelope =
        run {
            val payload =
                linkedMapOf<String, Any?>(
                    "strategyId" to strategyId,
                    "ts" to ts,
                )
            for ((k, v) in metadata) {
                if (k == "strategyId" || k == "ts") continue
                payload[k] = v
            }
            InsightsEnvelope(
                id = "strategy-started-$strategyId-$ts",
                seq = 0,
                ts = ts,
                strategyId = strategyId.takeIf { it.isNotBlank() },
                type = "strategy.started",
                payload = payload,
            )
        }

    fun strategyStopped(
        strategyId: String,
        ts: Long,
        flatten: Boolean,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "strategy-stopped-$strategyId-$ts",
            seq = 0,
            ts = ts,
            strategyId = strategyId.takeIf { it.isNotBlank() },
            type = "strategy.stopped",
            payload =
                mapOf(
                    "strategyId" to strategyId,
                    "flatten" to flatten,
                    "ts" to ts,
                ),
        )

    /**
     * The instance's currently-deployed strategy roster, emitted once per poll cycle.
     * Lets the collector tell live members from strategy ids that only linger from a
     * prior bench topology (e.g. after a reshard), instead of showing every id ever seen.
     * e.g. a 22-member bench emits {"strategies": ["forward_bench:s0", ...]} — 22 entries.
     */
    fun instanceRoster(
        ts: Long,
        strategyIds: Collection<String>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "roster-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "instance.roster",
            payload = mapOf("strategies" to strategyIds.toList()),
        )

    /** Durable-state health emitted when persistence becomes unsafe. */
    fun statePersistence(
        ts: Long,
        strategyId: String?,
        health: com.qkt.persistence.PersistenceHealth,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "persistence-${strategyId ?: "session"}-$ts",
            seq = 0,
            ts = ts,
            strategyId = strategyId,
            type = "state.persistence",
            payload =
                mapOf(
                    "enabled" to health.enabled,
                    "totalWrites" to health.totalWrites,
                    "slowWrites" to health.slowWrites,
                    "failedWrites" to health.failedWrites,
                    "consecutiveFailures" to health.consecutiveFailures,
                    "failureEpisodes" to health.failureEpisodes,
                    "queueSize" to health.queueSize,
                    "callerRunsTotal" to health.callerRunsTotal,
                ),
        )
}
