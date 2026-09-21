package com.qkt.app

import com.qkt.persistence.StatePersistor
import com.qkt.risk.RiskState
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

/**
 * Blocks new exposure while durable state cannot be written. Checked on the engine thread once
 * at start and on every heartbeat: a failing persistor halts entries (exits stay live) and each
 * new failure episode alerts once, e.g. a full disk produces one "CRITICAL disk failing" alert,
 * not one per second.
 */
internal class PersistenceHealthWatch(
    private val strategies: List<Pair<String, Strategy>>,
    private val persistor: StatePersistor,
    private val riskState: RiskState,
    private val sessionNotifier: SessionNotifier,
    private val insights: InsightsLifecycle,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)
    private var alertedPersistenceEpisode = 0L

    /** Read the persistor's health; halt entries while it is failing and alert on a new episode. */
    fun checkPersistenceHealth() {
        val health = persistor.healthSnapshot()
        if (!health.enabled) return
        val newFailureEpisode = health.failureEpisodes > alertedPersistenceEpisode
        if (!newFailureEpisode && health.consecutiveFailures == 0L) return
        val reason =
            "persistence failure: durable state is stale " +
                "(failedWrites=${health.failedWrites}, consecutiveFailures=${health.consecutiveFailures}, " +
                "queueSize=${health.queueSize}, " +
                "callerRunsTotal=${health.callerRunsTotal})"
        riskState.halt(reason, cancelWorkingOrders = false)
        if (!newFailureEpisode) return
        alertedPersistenceEpisode = health.failureEpisodes
        log.error("{}; blocking new exposure while keeping exits active", reason)
        val ownerStrategyId = strategies.firstOrNull()?.first.orEmpty()
        sessionNotifier.strategyError(ownerStrategyId, "PersistenceFailure") {
            "CRITICAL disk failing — persisted state is stale; new exposure halted"
        }
        insights.persistenceFailing(ownerStrategyId, health)
    }
}
