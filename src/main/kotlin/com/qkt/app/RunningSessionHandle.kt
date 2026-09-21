package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.execution.Trade
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.notify.StrategySummary
import com.qkt.observe.insights.TicketAttribution
import com.qkt.persistence.PersistenceHealth
import com.qkt.persistence.StatePersistor
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskState
import com.qkt.strategy.Strategy
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The handle [LiveSession.start] returns: how the daemon and operators talk to a running session.
 * Reads of engine state go through [EngineSnapshot] so they are answered on the engine thread;
 * commands either flip risk state directly or post onto the [EngineMailbox], e.g. `flatten()`
 * only enqueues [Inbound.Flatten] — the closes are published by the engine loop.
 */
internal class RunningSessionHandle(
    private val strategies: List<Pair<String, Strategy>>,
    private val mailbox: EngineMailbox,
    private val feed: TickFeed,
    private val pipeline: TradingPipeline,
    private val marketDataGate: MarketDataGate,
    private val persistor: StatePersistor,
    private val trades: RecentTrades,
    private val strategyPositions: StrategyPositionTracker,
    private val strategyPnL: StrategyPnL,
    private val priceTracker: MarketPriceTracker,
    private val instruments: InstrumentRegistry,
    private val riskState: RiskState,
    private val broker: Broker,
    private val ticketAttribution: TicketAttribution,
    private val clock: Clock,
    private val snapshot: EngineSnapshot,
    private val reconcileReport: SessionReconcileReport,
    private val shutdown: SessionShutdown,
    private val summaryRows: DailySummaryRows,
) : LiveSessionHandle,
    HaltReads by RiskHaltReads(riskState, strategies.map { it.first }) {
    private val control = mailbox.control
    private val tickQueue = mailbox.tickQueue
    private val droppedInboundTicks = mailbox.droppedInboundTicks
    private val terminated = mailbox.terminated
    private val clearRuleEdgesAtStop = mailbox.clearRuleEdgesAtStop

    private fun <T> engineSnapshot(read: () -> T): T = snapshot.engineSnapshot(read)

    override val running: Boolean get() = mailbox.running.get()

    override val droppedTicks: Long
        get() =
            (if (feed is LiveTickFeed) feed.droppedTicks.get() else 0L) +
                droppedInboundTicks.get() +
                pipeline.droppedLateTicks()

    override fun inboundQueueDepth(): Int = control.size + tickQueue.size

    override fun staleSymbols(): Map<String, Long> = marketDataGate.staleSymbols()

    override fun clockSkewedSymbols(): Map<String, Long> = marketDataGate.clockSkewedSymbols()

    override fun persistenceHealth(): PersistenceHealth = persistor.healthSnapshot()

    override fun reconcile(): ReconcileReport = reconcileReport.reconcile()

    override fun requestStop() = shutdown.requestStop()

    override fun stop() = shutdown.stop()

    override fun awaitTermination(timeout: Duration): Boolean =
        terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    override fun recentTrades(): List<Trade> = trades.snapshot()

    override fun positionsFor(strategyId: String): List<com.qkt.positions.Position> =
        engineSnapshot { strategyPositions.positionsFor(strategyId).values.toList() }

    override fun dailySummaryRows(): List<StrategySummary> =
        engineSnapshot { summaryRows.rows(strategyPnL, strategyPositions) }

    override fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo> =
        engineSnapshot { pipeline.orderManager.pendingStackLayerInfos() }

    override fun latencySnapshot(): com.qkt.observability.LatencyRegistry.Report = pipeline.latency.snapshot()

    override fun streamBrokers(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((_, strategy) in strategies) {
            if (strategy !is DslCompiledStrategy) continue
            for ((alias, key) in strategy.declaredStreams) {
                // Preserve declared casing for operator readability ("EXNESS" not "exness").
                out[alias] = key.broker
            }
        }
        return out
    }

    override fun realizedPnl(strategyId: String): java.math.BigDecimal = strategyPnL.realizedFor(strategyId)

    override fun pnlSnapshot(strategyId: String): SessionPnl =
        engineSnapshot {
            SessionPnl(
                equity = strategyPnL.equityFor(strategyId),
                balance = strategyPnL.balanceFor(strategyId),
                realized = strategyPnL.realizedFor(strategyId),
                unrealized = strategyPnL.unrealizedTotalFor(strategyId),
            )
        }

    override fun bookLegs(strategyId: String): List<com.qkt.risk.book.Leg> =
        engineSnapshot {
            strategyPositions.positionsFor(strategyId).values.mapNotNull { position ->
                if (position.quantity.signum() == 0) return@mapNotNull null
                val price = priceTracker.lastPrice(position.symbol) ?: position.avgEntryPrice
                val contractSize = instruments.lookup(position.symbol)?.contractSize ?: java.math.BigDecimal.ONE
                com.qkt.risk.book
                    .Leg(strategyId, position.symbol, position.quantity, price, contractSize)
            }
        }

    override fun halt(reason: String) {
        riskState.halt(reason)
    }

    override fun halt(
        reason: String,
        scope: com.qkt.risk.HaltScope,
    ) {
        riskState.halt(reason, scope)
    }

    override fun resume() {
        riskState.resume()
        // Operator resume must clear this session's strategy-scoped halts too —
        // a runaway-breaker halt was otherwise unreachable from `qkt resume` (#1064).
        for ((id, _) in strategies) riskState.resumeStrategy(id)
    }

    override fun flattenAndVerify(timeout: Duration): FlattenResult =
        VerifiedFlatten(broker, ticketAttribution, clock, strategies.map { it.first }, ::flatten).run(timeout)

    // Legacy fire-and-forget flatten stays engine-thread confined for internal callers.
    override fun flatten() {
        control.put(Inbound.Flatten)
    }

    override fun flattenForStop() {
        clearRuleEdgesAtStop.set(true)
        control.put(Inbound.Flatten)
    }
}
