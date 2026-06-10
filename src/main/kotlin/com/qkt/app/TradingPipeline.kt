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
import com.qkt.pnl.CommissionBook
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
     * Trading-cost ledger ([#335](https://github.com/elitekaycy/qkt/issues/335)). A backtest
     * passes a [CommissionBook] wrapping a real [com.qkt.pnl.PerLotCommission] so fills pay the
     * venue's commission; the cost is subtracted from realized PnL and tallied for the report.
     * Default charges nothing — live runs leave it so, since the real broker already bills.
     */
    val commissionBook: CommissionBook = CommissionBook(),
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
    /**
     * Runaway-strategy circuit breaker (#396). Non-null in live sessions; backtests
     * leave it null so high-frequency historical churn doesn't trip live thresholds.
     */
    private val runawayBreaker: com.qkt.risk.RunawayBreaker? = null,
    /**
     * Runtime market-data judgment (#395). Non-null in live sessions; backtests leave
     * it null — deterministic historical replay is exactly the data it was given.
     */
    private val marketDataGate: com.qkt.marketdata.MarketDataGate? = null,
) {
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    /** The primary-window aggregator (candle events on the bus); null when no window configured. */
    private val windowAggregator: CandleAggregator?

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
            // Risk-per-trade is a backtest-report feature; only record it there so the live
            // daemon's risk map doesn't grow unbounded.
            trackRisk = mode == Mode.BACKTEST,
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

        windowAggregator = if (candleWindow != null) CandleAggregator(bus, candleWindow) else null

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
        // subscribeFirst: the books must reflect this fill BEFORE any handler with venue
        // side effects runs — OrderManager cancels OCO siblings and dispatches children,
        // and the stack orchestrator risk-checks child tiers against position state.
        // Both subscribe earlier in construction order, so ordinary subscribe() here
        // would run them against a pre-fill book (#374, #377).
        bus.subscribeFirst<BrokerEvent.OrderFilled> { e ->
            if (latencyEnabled) latency.observeFill(e.clientOrderId, e.strategyId)
            // Phase 30: PositionTracker computes raw realized as qty * priceDiff. Apply
            // the instrument's contractSize here so dollar amounts match what the venue
            // reports. Default 1 preserves pre-Phase-30 behavior for symbols not in the
            // registry.
            val cs = instruments.lookup(e.symbol)?.contractSize ?: BigDecimal.ONE
            // Commission is a per-fill cash charge (#335). Net it out of the realized PnL the
            // accumulators see, so equity/drawdown/Sharpe go net; trade-level stats below stay
            // gross, and the report's commissionPaid bridges the two. Zero unless a backtest
            // configured a rate, so live and pre-cost-model runs are unchanged.
            val commission = commissionBook.charge(e.strategyId, e.symbol, e.quantity)
            // Venue-reported costs (MT5 deal commission/swap, Bybit execFee) net out the
            // same way the modeled commission does — equity and halt inputs must be
            // cost-true, or a strategy bleeding costs looks healthier than it is.
            val costs = commission.add(e.venueCosts)
            val rawRealized = positions.applyFill(e)
            val realized = rawRealized.multiply(cs)
            pnl.recordRealized(realized.subtract(costs))

            val rawStratRealized = strategyPositions.applyFill(e)
            val stratRealized = rawStratRealized.multiply(cs)
            strategyPnL.recordRealized(e.strategyId, stratRealized.subtract(costs))
            tradeHistory.recordTrade(e.strategyId, e.timestamp, stratRealized, e.symbol)
            riskState.onFill(e.strategyId, stratRealized.subtract(costs))
            if (stratRealized.signum() != 0) runawayBreaker?.recordClose(e.strategyId)
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
            val commission = commissionBook.charge(e.strategyId, e.symbol, e.quantity)
            val rawRealized = positions.applyFill(asFill)
            val realized = rawRealized.multiply(cs)
            pnl.recordRealized(realized.subtract(commission))

            val rawStratRealized = strategyPositions.applyFill(asFill)
            val stratRealized = rawStratRealized.multiply(cs)
            strategyPnL.recordRealized(e.strategyId, stratRealized.subtract(commission))
            riskState.onFill(e.strategyId, stratRealized)
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> runawayBreaker?.recordRejection(e.strategyId) }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            log.warn("Order rejected: ${e.clientOrderId} reason=${e.reason}")
            strategyPositions.forgetPending(e.strategyId, e.clientOrderId)
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { e ->
            // The losing leg of an OCO bracket cancels once its sibling fills; drop its
            // pre-registered open/close intent so the pending maps don't leak.
            strategyPositions.forgetPending(e.strategyId, e.clientOrderId)
        }
        bus.subscribe<RiskRejectedEvent> { e -> onRejected(e) }
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }
    }

    fun ingest(tick: Tick) {
        // Hard floor on the most exposed input boundary the engine has: one glitched
        // tick (zero/negative price, crossed quotes) marks every open position wrong,
        // fires engine-held triggers, and poisons indicators for a full window. Drop
        // it, count it, keep the last good price (#379). Identical in backtest and
        // live so the gate itself cannot cause divergence.
        if (!isValidTick(tick)) {
            val n = malformedTickCount.incrementAndGet()
            if (n == 1L || n % MALFORMED_TICK_LOG_EVERY == 0L) {
                log.error(
                    "dropping malformed tick #{} for {}: price={} bid={} ask={}",
                    n,
                    tick.symbol,
                    tick.price.toPlainString(),
                    tick.bid?.toPlainString(),
                    tick.ask?.toPlainString(),
                )
            }
            return
        }
        // The judgment layer above the floor: an implausible (outlier/crossed) tick is
        // dropped before it can poison indicators, marks, or triggers (#395).
        if (marketDataGate?.observe(tick) == com.qkt.marketdata.MarketDataGate.Verdict.OUTLIER) return
        engine.onTick(tick)
        candleHub.feed(tick)
        scheduleRunner.tick(tick.timestamp)
    }

    private companion object {
        /** Log cadence for malformed-tick drops — first occurrence, then every Nth. */
        const val MALFORMED_TICK_LOG_EVERY: Long = 1000L
    }

    /** Count of ticks dropped by [ingest]'s validation floor. */
    val malformedTickCount =
        java.util.concurrent.atomic
            .AtomicLong(0)

    private fun isValidTick(tick: Tick): Boolean {
        if (tick.price.signum() <= 0) return false
        val bid = tick.bid
        val ask = tick.ask
        if (bid != null && bid.signum() <= 0) return false
        if (ask != null && ask.signum() <= 0) return false
        if (bid != null && ask != null && bid > ask) return false
        return true
    }

    /**
     * Live-only quiet-market heartbeat. `LiveSession` calls this from a 1Hz timer
     * so a strategy's `SCHEDULE AT 09:00 UTC THEN …` still fires even if no ticks
     * arrived during that second. Backtest doesn't need it — tick replay drives
     * the heartbeat via [ingest] (#77).
     */
    fun scheduleHeartbeat(nowMs: Long) {
        scheduleRunner.tick(nowMs)
        // Time-driven candle close: a quiet symbol's bar must close when its window
        // ends, not when the next tick eventually arrives (live only — the heartbeat
        // doesn't run in backtest, where event-time is the only clock).
        windowAggregator?.flushClosed(nowMs)
        candleHub.flushClosed(nowMs)
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
        // Restart path: rebuild engines for parents that were open when the process
        // died — the restored leg supplies identity, the persisted tier state supplies
        // thresholds, windows, progress, and the original open-time anchor (#390).
        runCatching {
            for ((parentLegId, state) in persistor.loadPendingStacks(strategyId)) {
                val leg = strategyPositions.legById(strategyId, parentLegId) ?: continue
                orch.restoreEngine(
                    parentLegId = parentLegId,
                    parentSymbol = leg.symbol,
                    parentSide = leg.side,
                    parentEntryPrice = leg.entryPrice,
                    persisted = state,
                )
            }
        }.onFailure { e -> log.warn("stack tier restore failed for {}: {}", strategyId, e.message) }
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
