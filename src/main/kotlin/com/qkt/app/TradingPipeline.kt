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
    /**
     * Phase 25-followup ([#132](https://github.com/elitekaycy/qkt/issues/132)):
     * per-strategy trade history (last fill, last P&L, win/loss streaks). Default
     * is a fresh tracker per pipeline so backtests get isolated state; the daemon's
     * per-strategy `LiveSession` similarly gets its own.
     */
    val tradeHistory: com.qkt.pnl.TradeHistory = com.qkt.pnl.TradeHistory(),
    /**
     * Hot-path latency observation. Default reads `QKT_LATENCY_TRACKING` once at construction;
     * unset → disabled → every observe call short-circuits on the first line, no allocation
     * and no `nanoTime` call. See [com.qkt.observability.LatencyRegistry] and #150.
     */
    val latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
    /**
     * Resolver for `Timezone.BROKER` in DSL `SCHEDULE` triggers (#77). Returns the
     * broker's effective `ZoneId` for the given strategy, or `null` to indicate
     * the broker profile didn't supply `serverTzOffsetHours`. Defaults to null —
     * `BROKER` is only meaningful in live mode where a real broker profile exists.
     * Wired by [com.qkt.app.LiveSession] from the MT5 broker profile.
     */
    val brokerZoneIdFor: ((String) -> java.time.ZoneId?)? = null,
) {
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    val orderManager: OrderManager =
        OrderManager(
            broker,
            bus,
            priceTracker,
            clock,
            persistor,
            // An engine-managed exit id is `${bracketId}-sl`; the independent leg's id IS the
            // bracket id, so strip the suffix and look up that leg's venue ticket.
            closeTicketFor = { strategyId, exitId ->
                strategyPositions.ticketForLeg(strategyId, exitId.removeSuffix("-sl"))
            },
        )
    val latchManager: LatchManager =
        LatchManager(
            emit = { req ->
                when (val decision = riskEngine.approve(req)) {
                    is com.qkt.risk.Decision.Approve -> bus.publish(com.qkt.events.OrderEvent(req))
                    is com.qkt.risk.Decision.Reject ->
                        bus.publish(com.qkt.events.RiskRejectedEvent(req, decision.reason))
                }
            },
            clock = clock,
        )

    /**
     * Clock-driven scheduler for DSL `SCHEDULE` blocks (#77). Single instance shared
     * across every strategy. Heartbeat ticks from [ingest] for tick-driven advance,
     * plus [scheduleHeartbeat] from a 1Hz `LiveSession` timer for quiet markets.
     */
    val scheduleRunner: com.qkt.dsl.compile.ScheduleRunner =
        com.qkt.dsl.compile
            .ScheduleRunner(brokerZoneIdFor = brokerZoneIdFor)

    /** Per-(strategy, stage) latency trackers; see [com.qkt.observability.LatencyRegistry]. */
    val latency: com.qkt.observability.LatencyRegistry =
        com.qkt.observability.LatencyRegistry(
            enabled = latencyEnabled,
            strategyIds = strategies.map { it.first },
        )

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
                    tradeHistory = com.qkt.pnl.TradeHistoryViewImpl(tradeHistory, strategyId),
                )
            val rawEmit: (com.qkt.strategy.Signal) -> Unit = { sig ->
                val t0 = if (latencyEnabled) System.nanoTime() else 0L
                bus.publish(SignalEvent(sig))
                if (sig is com.qkt.strategy.Signal.CancelPendingForSymbol) {
                    orderManager.cancelPendingForSymbol(sig.symbol)
                } else if (sig is com.qkt.strategy.Signal.ArmLatch) {
                    latchManager.arm(sig.compiled, sig.ec)
                } else {
                    val request = sig.toOrderRequest(ids.next(), clock.now(), strategyId = strategyId)
                    if (request != null) {
                        logSubmitContext(request)
                        when (val decision = riskEngine.approve(request)) {
                            is Decision.Approve -> {
                                registerOcoEntryLegs(strategyId, request)
                                registerLegClose(strategyId, request)
                                bus.publish(OrderEvent(request))
                            }
                            is Decision.Reject -> bus.publish(RiskRejectedEvent(request, decision.reason))
                        }
                    }
                }
                if (latencyEnabled) {
                    latency.observe(
                        strategyId,
                        com.qkt.observability.LatencyStage.SIGNAL_TO_SUBMISSION,
                        System.nanoTime() - t0,
                    )
                }
            }
            val emit: (com.qkt.strategy.Signal) -> Unit = { sig ->
                if (gate()) rawEmit(sig)
            }
            if (strategy is com.qkt.dsl.compile.DslCompiledStrategy) {
                requireMultiPositionCapability(strategyId, strategy)
                for ((key, retention) in strategy.retentionByKey) candleHub.register(key, retention, strategyId)
                strategy.bindToHub(candleHub, ctx, emit)
                strategy.bindSchedules(scheduleRunner, ctx, clock.now(), emit)
                bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx, emit) }
                wireStackOrchestrator(strategy, strategyId, emit)
            } else {
                bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx, emit) }
                bus.subscribe<CandleEvent> { e -> strategy.onCandle(e.candle, ctx, emit) }
            }
        }
        bus.subscribe<TickEvent> { e -> latchManager.onTick(e.tick) }
        bus.subscribe<TickEvent> { e ->
            strategyPositions.onTick(e.tick.symbol, e.tick.price)
            riskState.onTick()
            riskEngine.evaluateHaltRules()
        }
        bus.subscribe<OrderEvent> { e ->
            // Record submit timestamp BEFORE the broker call, not after — backtest
            // brokers (PaperBroker, MT5BrokerSimulator) fill synchronously inside
            // `submit()`, so `OrderFilled` subscribers fire before control returns
            // here. If we recorded after, `observeFill` would find an empty map.
            if (latencyEnabled) latency.recordSubmit(e.request.id)
            orderManager.submit(e.request)
        }
        bus.subscribe<BrokerEvent.PositionReconciled> { e ->
            positions.reset(e.symbol, e.newQty, e.newAvgPx)
        }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            if (latencyEnabled) latency.observeFill(e.clientOrderId, e.strategyId)
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
            tradeHistory.recordTrade(e.strategyId, e.timestamp, stratRealized, e.symbol)
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
        scheduleRunner.tick(tick.timestamp)
    }

    /**
     * Live-only quiet-market heartbeat. `LiveSession` calls this from a 1Hz timer
     * so a strategy's `SCHEDULE AT 09:00 UTC THEN …` still fires even if no ticks
     * arrived during that second. Backtest doesn't need it — tick replay drives
     * the heartbeat via [ingest] (#77).
     */
    fun scheduleHeartbeat(nowMs: Long) {
        scheduleRunner.tick(nowMs)
    }

    fun ingestForWarmup(tick: Tick) {
        bus.publish(WarmupTickEvent(tick))
    }

    /**
     * INFO-log the order's price-bearing fields and the last price we saw for the
     * symbol at submit time. Gives operators a self-contained context line they can
     * pair with the subsequent `Order rejected` WARN (which carries only the
     * `clientOrderId`). Without this, a `BUY_STOP price must be above current ask`
     * rejection from the gateway is opaque — we don't see what we asked for or
     * what the last quote was when we asked.
     *
     * e.g. `submit Stop dsl-hedge_straddle--1 EXNESS:XAUUSDm BUY stopPrice=2350.50 lastPrice=2350.20`
     * paired with a later WARN tells the operator the BUY_STOP was ~30c above the
     * last-seen price but the gateway saw an ask that drifted past it in the
     * intervening latency window (see #185).
     */
    private fun logSubmitContext(request: com.qkt.execution.OrderRequest) {
        if (!log.isInfoEnabled) return
        val lastPrice = priceTracker.lastPrice(request.symbol)
        val kind = request::class.simpleName
        val priceFields =
            when (request) {
                is com.qkt.execution.OrderRequest.Stop -> "stopPrice=${request.stopPrice}"
                is com.qkt.execution.OrderRequest.StopLimit ->
                    "stopPrice=${request.stopPrice} limitPrice=${request.limitPrice}"
                is com.qkt.execution.OrderRequest.Limit -> "limitPrice=${request.limitPrice}"
                is com.qkt.execution.OrderRequest.IfTouched ->
                    "triggerPrice=${request.triggerPrice}" +
                        if (request.limitPrice != null) " limitPrice=${request.limitPrice}" else ""
                is com.qkt.execution.OrderRequest.TrailingStop ->
                    "trailAmount=${request.trailAmount} mode=${request.trailMode}"
                is com.qkt.execution.OrderRequest.TrailingStopLimit ->
                    "trailAmount=${request.trailAmount} mode=${request.trailMode} limitOffset=${request.limitOffset}"
                is com.qkt.execution.OrderRequest.ArmedTrailingStop ->
                    "entry=${request.entryPrice} trail=${request.trailDistance} mfe=${request.mfeThreshold}"
                is com.qkt.execution.OrderRequest.Bracket -> {
                    val sl =
                        when (val s = request.stopLoss) {
                            is com.qkt.execution.StopLossSpec.Fixed -> "stopLoss=${s.price}"
                            is com.qkt.execution.StopLossSpec.ArmedTrail ->
                                "stopLoss=armed(trail=${s.trailDistance}, mfe=${s.mfeThreshold})"
                        }
                    "takeProfit=${request.takeProfit} $sl entry=${request.entry::class.simpleName}"
                }
                else -> ""
            }
        log.info(
            "submit {} {} {} {} {} qty={} {} lastPrice={}",
            kind,
            request.id,
            request.symbol,
            request.side,
            request.timeInForce,
            request.quantity,
            priceFields,
            lastPrice ?: "unknown",
        )
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
     * A `CLOSE` of an independent leg is emitted as a market tagged with `closesLegId`
     * ([com.qkt.dsl.compile.ActionCompiler.closeSignalsFor]). Register the fill so the tracker
     * realizes that specific leg instead of netting into the primary. Works in backtest and
     * live; the venue-side close (by ticket) is handled separately by the MT5 broker.
     */
    private fun registerLegClose(
        strategyId: String,
        request: com.qkt.execution.OrderRequest,
    ) {
        if (request is com.qkt.execution.OrderRequest.Market && request.closesLegId != null) {
            strategyPositions.registerStackClose(strategyId, request.id, request.closesLegId)
        }
    }

    /**
     * For an `OCO_ENTRY` whose legs are brackets (e.g. a straddle), register each leg's entry
     * fill to open its own [com.qkt.positions.LegRole.INDEPENDENT] position leg — so a filled
     * long and a filled short coexist as two real positions instead of netting to zero — and
     * register each bracket's TP/SL exit to close that leg. Mirrors the stack machinery in
     * [wireStackOrchestrator], reusing OrderManager's deterministic `<bracket-id>-tp`/`-sl`
     * exit naming. No-op for any other request, so single-position strategies are unaffected.
     */
    private fun registerOcoEntryLegs(
        strategyId: String,
        request: com.qkt.execution.OrderRequest,
    ) {
        if (request !is com.qkt.execution.OrderRequest.StandaloneOCO) return
        for (leg in listOf(request.leg1, request.leg2)) {
            if (leg !is com.qkt.execution.OrderRequest.Bracket) continue
            strategyPositions.registerIndependentOpen(strategyId, leg.entry.id, leg.id)
            strategyPositions.registerStackClose(strategyId, "${leg.id}-tp", leg.id)
            strategyPositions.registerStackClose(strategyId, "${leg.id}-sl", leg.id)
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
                    parentQty = e.quantity,
                    tiers = pending.tiers,
                    closeWatchIds = pending.closeWatchIds,
                )
            } else {
                orch.onPossibleClose(e.clientOrderId)
            }
        }
    }
}
