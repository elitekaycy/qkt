package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.SequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.execution.toOrderRequest
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.pnl.StrategyPnLViewImpl
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.StrategyPositionViewImpl
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Mode
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * The reusable end-to-end wiring of bus + engine + risk + order management + broker.
 *
 * Used by both `Backtest` and `LiveSession` — the only difference between the two is
 * the tick feed and the clock; the pipeline is identical. That symmetry is what makes
 * backtest = live-paper given the same ticks (verified by the parity regression test).
 */
class TradingPipeline(
    val clock: Clock,
    val ids: IdGenerator,
    val sequencer: SequenceGenerator,
    val priceTracker: MarketPriceTracker,
    val positions: PositionTracker,
    val pnl: PnLCalculator,
    val strategyPositions: StrategyPositionTracker,
    val strategyPnL: StrategyPnL,
    val bus: EventBus,
    val broker: Broker,
    val engine: Engine,
    val strategies: List<Pair<String, Strategy>>,
    val riskEngine: RiskEngine,
    val riskState: com.qkt.risk.RiskState,
    val mode: Mode,
    val calendar: TradingCalendar,
    val source: MarketSource,
    val candleWindow: TimeWindow? = null,
    val candleHub: com.qkt.dsl.compile.CandleHub =
        com.qkt.dsl.compile
            .CandleHub(),
    val onFilled: (Trade, BigDecimal, String) -> Unit = { _, _, _ -> },
    val onRejected: (RiskRejectedEvent) -> Unit = {},
    val onCandle: (Candle) -> Unit = {},
    val gate: () -> Boolean = { true },
    val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
    /**
     * Per-instrument venue metadata (Phase 30). Default [NoopInstrumentRegistry] preserves
     * pre-Phase-30 behavior — strategies using `SIZING RISK $` need a real registry
     * (wired by [com.qkt.app.LiveSession] for live, by `Backtest.fromStore` for backtest).
     */
    val instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
) {
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    val orderManager: OrderManager = OrderManager(broker, bus, priceTracker, clock, persistor)

    init {
        require(strategies.map { it.first }.toSet().size == strategies.size) {
            "Strategy IDs must be unique: ${strategies.map { it.first }}"
        }
        require(strategies.all { it.first.isNotBlank() }) {
            "Strategy ID must be non-blank"
        }

        if (candleWindow != null) CandleAggregator(bus, candleWindow)

        bus.subscribe<WarmupTickEvent> { e -> priceTracker.update(e.tick.symbol, e.tick.price) }

        strategies.forEach { (strategyId, strategy) ->
            val ctx =
                StrategyContext(
                    strategyId = strategyId,
                    mode = mode,
                    clock = clock,
                    calendar = calendar,
                    source = source,
                    positions = StrategyPositionViewImpl(strategyPositions, strategyId),
                    pnl = StrategyPnLViewImpl(strategyPnL, strategyId),
                    risk = com.qkt.risk.RiskViewImpl(riskState, strategyId),
                    instruments = instruments,
                )
            val rawEmit: (com.qkt.strategy.Signal) -> Unit = { sig ->
                bus.publish(SignalEvent(sig))
                if (sig is com.qkt.strategy.Signal.CancelPendingForSymbol) {
                    orderManager.cancelPendingForSymbol(sig.symbol)
                } else {
                    val request = sig.toOrderRequest(ids.next(), clock.now(), strategyId = strategyId)
                    if (request != null) {
                        when (val decision = riskEngine.approve(request)) {
                            is Decision.Approve -> bus.publish(OrderEvent(request))
                            is Decision.Reject -> bus.publish(RiskRejectedEvent(request, decision.reason))
                        }
                    }
                }
            }
            val emit: (com.qkt.strategy.Signal) -> Unit = { sig ->
                if (gate()) rawEmit(sig)
            }
            if (strategy is com.qkt.dsl.compile.DslCompiledStrategy) {
                requireMultiPositionCapability(strategyId, strategy)
                for ((key, retention) in strategy.retentionByKey) candleHub.register(key, retention, strategyId)
                strategy.bindToHub(candleHub, ctx, emit)
                bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx, emit) }
                wireStackOrchestrator(strategy, strategyId, emit)
            } else {
                bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx, emit) }
                bus.subscribe<CandleEvent> { e -> strategy.onCandle(e.candle, ctx, emit) }
            }
        }
        bus.subscribe<TickEvent> { e ->
            strategyPositions.onTick(e.tick.symbol, e.tick.price)
            riskState.onTick()
            riskEngine.evaluateHaltRules()
        }
        bus.subscribe<OrderEvent> { e ->
            orderManager.submit(e.request)
        }
        bus.subscribe<BrokerEvent.PositionReconciled> { e ->
            positions.reset(e.symbol, e.newQty, e.newAvgPx)
        }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            // Phase 30: PositionTracker computes raw realized as qty * priceDiff. Apply
            // the instrument's contractSize here so dollar amounts match what the venue
            // reports. Default 1 preserves pre-Phase-30 behavior for symbols not in the
            // registry.
            val cs = instruments.lookup(e.symbol)?.contractSize ?: BigDecimal.ONE
            val rawRealized = positions.applyFill(e)
            val realized = rawRealized.multiply(cs)
            pnl.recordRealized(realized)

            val rawStratRealized = strategyPositions.applyFill(e)
            val stratRealized = rawStratRealized.multiply(cs)
            strategyPnL.recordRealized(e.strategyId, stratRealized)
            riskState.onFill(e.strategyId, stratRealized)
            riskEngine.evaluateHaltRules()

            val trade =
                Trade(
                    orderId = e.clientOrderId,
                    symbol = e.symbol,
                    price = e.price,
                    quantity = e.quantity,
                    side = e.side,
                    timestamp = e.timestamp,
                )
            bus.publish(TradeEvent(trade))
            onFilled(trade, realized, e.strategyId)
        }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e ->
            val asFill =
                BrokerEvent.OrderFilled(
                    clientOrderId = e.clientOrderId,
                    brokerOrderId = e.brokerOrderId,
                    symbol = e.symbol,
                    side = e.side,
                    price = e.price,
                    quantity = e.quantity,
                    strategyId = e.strategyId,
                    timestamp = e.timestamp,
                )
            val cs = instruments.lookup(e.symbol)?.contractSize ?: BigDecimal.ONE
            val rawRealized = positions.applyFill(asFill)
            val realized = rawRealized.multiply(cs)
            pnl.recordRealized(realized)

            val rawStratRealized = strategyPositions.applyFill(asFill)
            val stratRealized = rawStratRealized.multiply(cs)
            strategyPnL.recordRealized(e.strategyId, stratRealized)
            riskState.onFill(e.strategyId, stratRealized)
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            log.warn("Order rejected: ${e.clientOrderId} reason=${e.reason}")
        }
        bus.subscribe<RiskRejectedEvent> { e -> onRejected(e) }
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }
    }

    fun ingest(tick: Tick) {
        engine.onTick(tick)
        candleHub.feed(tick)
    }

    fun ingestForWarmup(tick: Tick) {
        bus.publish(WarmupTickEvent(tick))
    }

    /**
     * Phase 27: refuse to deploy a strategy whose `STACK_AT` symbols route to a broker
     * that doesn't declare [com.qkt.broker.OrderTypeCapability.MULTI_POSITION_PER_SYMBOL].
     * The capability is checked per-symbol via [com.qkt.broker.Broker.capabilitiesFor]
     * so [com.qkt.broker.CompositeBroker] routing differences across symbols are honored.
     */
    private fun requireMultiPositionCapability(
        strategyId: String,
        strategy: com.qkt.dsl.compile.DslCompiledStrategy,
    ) {
        for (symbol in strategy.multiPositionPerSymbolSymbols) {
            val caps = broker.capabilitiesFor(symbol)
            require(com.qkt.broker.OrderTypeCapability.MULTI_POSITION_PER_SYMBOL in caps) {
                "Strategy '$strategyId' uses STACK_AT on $symbol but routing broker " +
                    "'${broker.name}' does not declare MULTI_POSITION_PER_SYMBOL"
            }
        }
    }

    /**
     * Phase 27: per-DSL-strategy stack lifecycle. The orchestrator owns one [com.qkt.dsl.compile.StackEngine]
     * per active PRIMARY leg with `STACK_AT` clauses. On parent-fill it consumes the
     * matching [com.qkt.dsl.compile.PendingStack] populated by the action compiler.
     * Stack-emitted signals go through the same [emit] path as user-emitted signals so
     * risk / ordering / id allocation behave uniformly.
     *
     * Parent close detection: when an [BrokerEvent.OrderFilled] for the strategy is NOT
     * a known primary entry (no pending entry to consume), it's treated as a possible
     * close — engines watching that id terminate. The action compiler predicts a
     * Bracket parent's TP/SL ids using OrderManager's deterministic naming. Native
     * broker brackets and manual closes are not yet covered.
     */
    private fun wireStackOrchestrator(
        strategy: com.qkt.dsl.compile.DslCompiledStrategy,
        strategyId: String,
        emit: (com.qkt.strategy.Signal) -> Unit,
    ) {
        val orch =
            com.qkt.dsl.compile.StackOrchestrator(
                clock = clock,
                emit = emit,
                strategyId = strategyId,
                persistor = persistor,
                onStackBracketEmit = { bracket, parentLegId ->
                    // Pre-register the stack's entry fill → STACK leg open, and the bracket's
                    // TP/SL ids → STACK leg close (using OrderManager.submitBracketFallback's
                    // deterministic `${bracket.id}-tp` / `-sl` naming).
                    strategyPositions.registerStackOpen(
                        strategyId = strategyId,
                        clientOrderId = bracket.entry.id,
                        stackLegId = bracket.id,
                        parentLegId = parentLegId,
                    )
                    strategyPositions.registerStackClose(
                        strategyId = strategyId,
                        clientOrderId = "${bracket.id}-tp",
                        stackLegId = bracket.id,
                    )
                    strategyPositions.registerStackClose(
                        strategyId = strategyId,
                        clientOrderId = "${bracket.id}-sl",
                        stackLegId = bracket.id,
                    )
                },
            )
        bus.subscribe<TickEvent> { e -> orch.onTick(e.tick.symbol, e.tick.price) }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            if (e.strategyId != strategyId) return@subscribe
            val pending = strategy.pendingStacks.consume(e.clientOrderId)
            if (pending != null) {
                orch.onPrimaryFilled(
                    parentLegId = pending.parentClientOrderId,
                    parentSymbol = pending.symbol,
                    parentSide = pending.side,
                    parentEntryPrice = e.price,
                    tiers = pending.tiers,
                    closeWatchIds = pending.closeWatchIds,
                )
            } else {
                orch.onPossibleClose(e.clientOrderId)
            }
        }
    }
}
