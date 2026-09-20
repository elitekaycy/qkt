package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.accounting.ConvertedMoney
import com.qkt.backtest.FillState
import com.qkt.broker.Broker
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.SequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.ScheduleRunner
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.FillAccountingKind
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.observability.LatencyRegistry
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.StatePersistor
import com.qkt.pnl.BookBalanceView
import com.qkt.pnl.CommissionBook
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.pnl.TradeHistory
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.PacerLedger
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.risk.RunawayBreaker
import com.qkt.strategy.Mode
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * The reusable end-to-end wiring of bus + engine + risk + order management + broker.
 *
 * Used by both `Backtest` and `LiveSession` — the only difference between the two is
 * the tick feed and the clock; the pipeline is identical. That symmetry is what makes
 * backtest = live-paper given the same ticks (verified by the parity regression test).
 * Bus subscription order is dispatch order, so the wiring in `init` must keep its sequence.
 */
class TradingPipeline(
    val clock: Clock,
    val ids: IdGenerator,
    val sequencer: SequenceGenerator,
    val priceTracker: MarketPriceTracker,
    val positions: PositionProvider,
    val pnl: PnLCalculator,
    val strategyPositions: StrategyPositionTracker,
    val strategyPnL: StrategyPnL,
    val bus: EventBus,
    val broker: Broker,
    val engine: Engine,
    val strategies: List<Pair<String, Strategy>>,
    val riskEngine: RiskEngine,
    val riskState: RiskState,
    val mode: Mode,
    /** Replay's stand-in for the live heartbeat's candle-close grace (#1138); see [CandleWindowCloser]. */
    val replayCandleCloseGraceMs: Long = LiveSession.DEFAULT_CANDLE_CLOSE_GRACE_MS,
    val replayHeartbeatIntervalMs: Long = 1_000L,
    /**
     * Venue position model per symbol (#1071): HEDGING books each entry as its own leg, UNKNOWN nets.
     * Backtests wire the simulation's configured mode; live wires the venue-reported mode.
     */
    val positionMode: (symbol: String) -> PositionAccountingMode = { PositionAccountingMode.UNKNOWN },
    val calendar: TradingCalendar,
    val source: MarketSource,
    val candleWindow: TimeWindow? = null,
    val candleHub: CandleHub = CandleHub(),
    val onFilled: (Trade, BigDecimal, String) -> Unit = { _, _, _ -> },
    val onAccountedFill: (Trade, ConvertedMoney, String, FillState) -> Unit = { _, _, _, _ -> },
    val onRejected: (RiskRejectedEvent) -> Unit = {},
    val onCandle: (Candle) -> Unit = {},
    val preCandle: (Candle) -> Unit = {},
    val gate: () -> Boolean = { true },
    val gateFor: (String) -> Boolean = { true },
    val persistor: StatePersistor = NoopStatePersistor(),
    /** Per-instrument venue metadata (Phase 30); `SIZING RISK $` needs a real registry. */
    val instruments: InstrumentRegistry = NoopInstrumentRegistry,
    /** Modeled commission (#335); live leaves it charging nothing since the broker bills. */
    val commissionBook: CommissionBook = CommissionBook(),
    val accounting: AccountingEngine = AccountingEngine(),
    /** Per-strategy trade history (#132); a fresh tracker per pipeline isolates state. */
    val tradeHistory: TradeHistory = TradeHistory(persistor = persistor),
    /** Operator alert hook for a filled stack layer whose venue-side protection failed. */
    val onProtectionFailure: (strategyId: String, message: String) -> Unit = { _, _ -> },
    val pacerLedger: PacerLedger = PacerLedger(),
    private val pacerCooldownDurationMs: Long? = null,
    private val pacerCooldownAfterConsecutive: Int = 1,
    private val pacerCooldownDurationMsFor: ((String) -> Long?)? = null,
    private val pacerCooldownAfterConsecutiveFor: ((String) -> Int)? = null,
    /** Hot-path latency observation (#150); read once at construction. Off: observe calls short-circuit. */
    val latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
    /** Resolves `Timezone.BROKER` for DSL `SCHEDULE` triggers (#77); null outside live. */
    val brokerZoneIdFor: ((String) -> ZoneId?)? = null,
    /** Runaway-strategy circuit breaker (#396); live only. */
    private val runawayBreaker: RunawayBreaker? = null,
    /** Runtime market-data judgment (#395); live only, see [TickIngest]. */
    private val marketDataGate: MarketDataGate? = null,
    /** Book scale (de-risk x allocation weight) for new risk-increasing orders; default 1.0. */
    private val bookScaleFor: (String) -> BigDecimal = { BigDecimal.ONE },
    /** Portfolio book balance read by `SIZING … RISK OF BOOK`; null outside a portfolio deploy. */
    private val bookBalance: BookBalanceView? = null,
) {
    private val exitHookManager = ExitHookManager(persistor)
    val orderManager: OrderManager =
        pipelineOrderManager(
            broker,
            bus,
            priceTracker,
            clock,
            persistor,
            strategyPositions,
            positions,
            riskState,
            mode,
            instruments,
            onProtectionFailure,
            positionMode,
        )
    val latchManager: LatchManager = LatchManager(clock = clock)

    /** Clock-driven scheduler for DSL `SCHEDULE` blocks (#77), advanced by ticks and [scheduleHeartbeat]. */
    val scheduleRunner: ScheduleRunner = ScheduleRunner(brokerZoneIdFor = brokerZoneIdFor)

    /** Per-(strategy, stage) latency trackers; see [LatencyRegistry]. */
    val latency: LatencyRegistry = LatencyRegistry(enabled = latencyEnabled, strategyIds = strategies.map { it.first })
    private val contexts =
        StrategyContextFactory(
            mode,
            clock,
            calendar,
            source,
            strategyPositions,
            strategyPnL,
            riskState,
            instruments,
            accounting,
            tradeHistory,
            pacerLedger,
            pacerCooldownDurationMs,
            pacerCooldownAfterConsecutive,
            pacerCooldownDurationMsFor,
            pacerCooldownAfterConsecutiveFor,
            orderManager,
            bookBalance,
        )
    private val submitter =
        OrderSubmitter(
            ids,
            clock,
            bus,
            riskEngine,
            positions,
            strategyPositions,
            priceTracker,
            exitHookManager,
            positionMode,
            bookScaleFor,
        )
    private val strategyBinder =
        StrategyBinder(
            bus,
            clock,
            persistor,
            broker,
            source,
            bookBalance,
            candleHub,
            scheduleRunner,
            latchManager,
            exitHookManager,
            orderManager,
            tradeHistory,
            strategyPositions,
            contexts,
            submitter,
            gate,
            gateFor,
            latency,
            latencyEnabled,
        )
    private val booker =
        ExecutionBooker(
            riskState,
            positions,
            strategyPositions,
            instruments,
            commissionBook,
            accounting,
            orderManager,
            positionMode,
        )
    private val fold =
        AccountedFillFold(pnl, strategyPnL, tradeHistory, pacerLedger, runawayBreaker, riskState, riskEngine)
    private val outcomes =
        OrderOutcomeWiring(
            bus,
            orderManager,
            exitHookManager,
            booker,
            fold,
            ExecutionReporter(bus, onFilled, onAccountedFill),
            runawayBreaker,
            onRejected,
            latency,
            latencyEnabled,
            strategies,
        )
    private val nonExecution = NonExecutionAccounting(riskState, bus, accounting, clock)
    private val equitySampler = AccountEquitySeriesSampler(strategies, candleHub, riskState)
    private val candleCloser: CandleWindowCloser
    private val tickIngest: TickIngest

    init {
        riskEngine.bindPendingExposure(orderManager)
        require(strategies.map { it.first }.toSet().size == strategies.size) {
            "Strategy IDs must be unique: ${strategies.map { it.first }}"
        }
        require(strategies.all { it.first.isNotBlank() }) { "Strategy ID must be non-blank" }
        val windowAggregator = if (candleWindow != null) CandleAggregator(bus, candleWindow) else null
        candleCloser =
            CandleWindowCloser(windowAggregator, candleHub, replayCandleCloseGraceMs, replayHeartbeatIntervalMs)
        tickIngest =
            TickIngest(engine, marketDataGate, equitySampler, candleCloser, candleHub, scheduleRunner, mode)
        bus.subscribe<WarmupTickEvent> { e -> priceTracker.update(e.tick) }
        bus.subscribe<CandleEvent> { e -> preCandle(e.candle) }
        strategyBinder.bindAll(strategies)
        bus.subscribe<TickEvent> { e -> latchManager.onTick(e.tick) }
        bus.subscribe<CandleEvent> { e -> latchManager.onCandle(e.candle) }
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
            strategyPositions.reconcileNet(
                e.symbol,
                e.newQty,
                e.newAvgPx,
                openedAt = e.timestamp,
                source = e.source,
                ticket = e.ticket,
                strategyId = e.strategyId,
            )
        }
        outcomes.subscribe()
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }
    }

    /** Count of ticks dropped by [ingest]'s validation floor. */
    val malformedTickCount: AtomicLong get() = tickIngest.malformedTickCount

    /** Ingest one tick; see [TickIngest]. */
    fun ingest(tick: Tick) = tickIngest.ingest(tick)

    /** Book a financing accrual (swap) through the same accounted-event fold as an execution. */
    internal fun applyFinancing(
        strategyId: String,
        amount: BigDecimal,
    ) = nonExecution.publish(strategyId, amount, FillAccountingKind.FINANCING, "financing:$strategyId")

    /**
     * Book what the venue realized on a leg that closed while the daemon was down. Same fold,
     * same accumulators, same audit trail as an execution the engine saw itself.
     */
    fun applyReconciledRealized(
        strategyId: String,
        amount: BigDecimal,
        legId: String,
    ) = nonExecution.publish(strategyId, amount, FillAccountingKind.RECONCILE, "reconcile:$legId", legId)

    /**
     * Live-only quiet-market heartbeat from a 1Hz `LiveSession` timer: schedules fire and a quiet
     * symbol's bar closes when its window ends, lagging the wall clock by [candleCloseGraceMs] so an
     * in-flight tick stamped just before the boundary still lands in its bar (#77, #1058).
     */
    fun scheduleHeartbeat(
        nowMs: Long,
        candleCloseGraceMs: Long = 0L,
    ) {
        orderManager.persistTrailingStateIfDirty()
        orderManager.retryHaltCancellations(nowMs)
        scheduleRunner.tick(nowMs)
        equitySampler.sample(nowMs)
        candleCloser.flushClosed(nowMs - candleCloseGraceMs)
    }

    /** Close every window ended at [nowMs] without running live-only schedule and broker maintenance. */
    internal fun flushReplayCandles(nowMs: Long) = candleCloser.flushClosed(nowMs)

    /** Late ticks rejected after their candle was finalized; see [CandleWindowCloser.droppedLateTicks]. */
    fun droppedLateTicks(): Long = candleCloser.droppedLateTicks()

    fun ingestForWarmup(tick: Tick) = ingestForWarmup(tick, sourceTimeframeMs = null)

    internal fun ingestForWarmup(
        tick: Tick,
        sourceTimeframeMs: Long?,
    ) {
        bus.publish(WarmupTickEvent(tick, sourceTimeframeMs = sourceTimeframeMs))
    }
}
