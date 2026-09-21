package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.observe.insights.BrokerStatePoller
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import com.qkt.observe.insights.SharedDealFetch
import com.qkt.observe.insights.TicketAttribution
import com.qkt.pnl.StrategyPnL
import com.qkt.strategy.Strategy
import java.util.concurrent.atomic.AtomicReference

/**
 * Broker truth → insights: account state, per-ticket positions, and deal history
 * polled on the poller's own thread, off the engine loop. Replaces the retired
 * engine-thread ledger snapshots — dashboards read state.* / broker.deal now. Only runs with a
 * sink and the `STATE` family enabled, e.g. `state_poll_ms: 10000` samples every ten seconds.
 */
internal class InsightsStatePolling(
    private val strategies: List<Pair<String, Strategy>>,
    private val insightsSink: InsightsSink?,
    private val insightsEvents: Set<InsightsEventFamily>,
    private val insights: InsightsLifecycle,
    private val ticketAttribution: TicketAttribution,
    private val insightsDeployedIds: () -> Collection<String>,
    private val insightsStatePollMs: Long,
    private val insightsSharedDeals: SharedDealFetch,
    private val insightsDealBackfillDays: Long,
    private val clock: Clock,
) {
    /**
     * Start the poller over [brokerStatePollerBrokers], or return null when it is not enabled.
     * The handle is built at the end of start(); the poller samples equity through [handleRef] so
     * the read runs as an engine-thread snapshot rather than a racy cross-thread read.
     */
    fun start(
        brokerStatePollerBrokers: List<Broker>,
        handleRef: AtomicReference<LiveSessionHandle?>,
        strategyPnL: StrategyPnL,
    ): BrokerStatePoller? =
        if (insightsSink != null &&
            com.qkt.observe.insights.InsightsEventFamily.STATE in insightsEvents &&
            brokerStatePollerBrokers.isNotEmpty()
        ) {
            com.qkt.observe.insights
                .BrokerStatePoller(
                    brokers = brokerStatePollerBrokers,
                    sink = insightsSink,
                    attribution = ticketAttribution,
                    deployedIds = { (strategies.map { it.first } + insightsDeployedIds()).distinct() },
                    rosterIds = { insights.strategyIds() },
                    pollIntervalMs = insightsStatePollMs,
                    sharedDeals = insightsSharedDeals,
                    backfillDays = insightsDealBackfillDays,
                    emitDeals = com.qkt.observe.insights.InsightsEventFamily.DEAL in insightsEvents,
                    strategyEquity = {
                        val handle = handleRef.get()
                        if (handle == null) {
                            emptyList()
                        } else {
                            val now = clock.now()
                            insights.strategyIds().map { strategyId ->
                                val pnl = handle.pnlSnapshot(strategyId)
                                com.qkt.observe.insights.InsightsTranslate.equitySnapshot(
                                    ts = now,
                                    strategyId = strategyId,
                                    realized = pnl.realized,
                                    unrealized = pnl.unrealized,
                                    equity = pnl.equity,
                                    startingBalance = strategyPnL.startingBalanceFor(strategyId),
                                )
                            }
                        }
                    },
                ).also { it.start() }
        } else {
            null
        }
}
