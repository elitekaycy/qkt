package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ScheduleRunner
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.source.MarketSource
import com.qkt.observability.LatencyRegistry
import com.qkt.persistence.StatePersistor
import com.qkt.pnl.BookBalanceView
import com.qkt.pnl.TradeHistory
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Strategy

/**
 * Binds each strategy into the pipeline: restores its trade history, builds its context and
 * [StrategySignalEmitter], and subscribes it to ticks and candles. A DSL strategy is also
 * capability-checked, registered on the candle hub, schedules, exit hooks and latches, audited,
 * and given a stack orchestrator. Strategies bind in list order, which fixes their dispatch order.
 */
internal class StrategyBinder(
    private val bus: EventBus,
    private val clock: Clock,
    private val persistor: StatePersistor,
    private val broker: Broker,
    private val source: MarketSource,
    private val bookBalance: BookBalanceView?,
    private val candleHub: CandleHub,
    private val scheduleRunner: ScheduleRunner,
    private val latchManager: LatchManager,
    private val exitHookManager: ExitHookManager,
    private val orderManager: OrderManager,
    private val tradeHistory: TradeHistory,
    strategyPositions: StrategyPositionTracker,
    private val contexts: StrategyContextFactory,
    private val submitter: OrderSubmitter,
    private val gate: () -> Boolean,
    private val gateFor: (String) -> Boolean,
    private val latency: LatencyRegistry,
    private val latencyEnabled: Boolean,
) {
    private val audit = DslEvaluationAudit(bus, candleHub)
    private val stackBinder = StackOrchestratorBinder(clock, bus, persistor, strategyPositions)

    /** Bind every strategy, in order. */
    fun bindAll(strategies: List<Pair<String, Strategy>>) {
        strategies.forEach { (strategyId, strategy) -> bind(strategyId, strategy) }
    }

    private fun bind(
        strategyId: String,
        strategy: Strategy,
    ) {
        tradeHistory.restore(strategyId)
        val ctx = contexts.create(strategyId)
        val emit =
            StrategySignalEmitter(
                strategyId,
                strategy,
                ctx,
                bus,
                orderManager,
                latchManager,
                submitter,
                gate,
                gateFor,
                latency,
                latencyEnabled,
            )
        if (strategy is DslCompiledStrategy) {
            requireMultiPositionCapability(strategyId, strategy, broker)
            requireVolumeCapability(strategyId, strategy, source)
            requireBookCapability(strategyId, strategy, bookBalance)
            strategy.bindStatePersistor(strategyId, persistor)
            val hubKeys = strategy.declaredStreams.values.toSet() + strategy.retentionByKey.keys
            for (key in hubKeys) {
                candleHub.register(key, strategy.retentionByKey[key] ?: 1, strategyId)
            }
            audit.publishStreamCandles(strategy)
            for (key in strategy.declaredStreams.values) {
                candleHub.onClosed(key, strategyId) { candle -> latchManager.onCandle(candle) }
            }
            audit.publishEvaluations(strategyId, strategy)
            strategy.bindToHub(candleHub, ctx, emit)
            exitHookManager.bind(strategyId, strategy, emit)
            strategy.bindSchedules(scheduleRunner, ctx, clock.now(), emit)
            bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx, emit) }
            // Candles still flow to the strategy object: a hub-bound DSL strategy's onCandle
            // returns immediately, but a wrapper (GatedChild) relies on this hook for its
            // flatten-on-gate-deactivate transition — hub binding carries only the inner rules.
            bus.subscribe<CandleEvent> { e -> strategy.onCandle(e.candle, ctx, emit) }
            stackBinder.bind(strategy, strategyId, emit)
        } else {
            bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx, emit) }
            bus.subscribe<CandleEvent> { e -> strategy.onCandle(e.candle, ctx, emit) }
        }
    }
}
