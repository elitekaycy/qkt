package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.CompositeBroker
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.SignalEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.windowMs
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Runs one or more strategies against a live or paper data source, end to end.
 *
 * Owns its own [com.qkt.bus.EventBus], [com.qkt.engine.Engine], [Broker]
 * (constructed by the typed [BrokerFactory] registry per session), [PositionTracker],
 * [PnLCalculator], and [RiskEngine]. The daemon spawns one session per deployed
 * `.qkt` file; portfolios fan out into one session per child strategy.
 *
 * The session pulls from a [LiveTickFeed], runs warmup if the strategy is [Warmable],
 * then enters the live loop where ticks are ingested, signals are routed, and trades
 * land back on the bus. Closing the session shuts everything down cleanly.
 */
class LiveSession(
    private val strategies: List<Pair<String, Strategy>>,
    private val rules: List<RiskRule> = emptyList(),
    private val haltRules: List<HaltRule> = emptyList(),
    private val source: MarketSource,
    private val symbols: List<String>,
    private val candleWindow: TimeWindow? = null,
    private val clock: Clock = SystemClock(),
    private val calendar: TradingCalendar = TradingCalendar.fxDefault(),
    private val warmupOverride: WarmupSpec? = null,
    private val mdcStrategy: String? = null,
    private val candleHub: com.qkt.dsl.compile.CandleHub? = null,
    private val onWarmupTick: (Tick) -> Unit = {},
    private val onTrade: (Trade, java.math.BigDecimal, String) -> Unit = { _, _, _ -> },
    private val onSignal: (Signal) -> Unit = {},
    private val gate: () -> Boolean = { true },
    private val brokerFactories: Map<String, BrokerFactory> = emptyMap(),
    private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
    /**
     * When `false` (default), a mismatch between broker positions and persisted leg
     * state at deploy time throws [com.qkt.app.ReconcileException] — the strategy
     * refuses to start. Operators set this to `true` to attach broker positions as
     * fresh PRIMARY legs and proceed (the `qkt deploy --reconcile=ignore-mismatches`
     * CLI flag).
     */
    private val ignoreMismatches: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    /**
     * Three-way reconcile: persisted leg state + broker positions → attached LegBook
     * or refusal. Runs once at startup before the engine thread takes ticks.
     */
    private fun reconcileOrPreload(
        strategyPositions: com.qkt.positions.StrategyPositionTracker,
        broker: Broker,
    ) {
        val brokerByQktSymbol = runCatching { broker.getOpenPositions() }.getOrElse { emptyMap() }
        val reconciler = com.qkt.persistence.LegBookReconciler(persistor)
        for ((strategyId, _) in strategies) {
            for (symbol in symbols) {
                val brokerForSymbol = brokerByQktSymbol[symbol] ?: emptyList()
                val outcome = reconciler.reconcile(strategyId, symbol, brokerForSymbol)
                when (outcome) {
                    is com.qkt.persistence.LegBookReconciler.Outcome.Attached -> {
                        for (leg in outcome.legBook.all()) {
                            if (leg.role == com.qkt.positions.LegRole.PRIMARY) {
                                // Primary preload uses applyFill semantics — but the engine
                                // hasn't run yet, so we rebuild via the persistor preload path.
                                strategyPositions.preloadFromPersistor(strategyId, symbol)
                            }
                        }
                    }
                    is com.qkt.persistence.LegBookReconciler.Outcome.Mismatch -> {
                        if (!ignoreMismatches) {
                            throw ReconcileException(
                                "$strategyId/$symbol: ${outcome.details}. " +
                                    "Pass --reconcile=ignore-mismatches to attach broker positions as PRIMARY.",
                            )
                        }
                        log.warn(
                            "Reconcile mismatch (ignored): {}/{} — {}",
                            strategyId,
                            symbol,
                            outcome.details,
                        )
                        // Attach broker positions as fresh PRIMARY legs.
                        for (pos in brokerForSymbol) {
                            val side =
                                if (pos.quantity.signum() >= 0) {
                                    com.qkt.common.Side.BUY
                                } else {
                                    com.qkt.common.Side.SELL
                                }
                            strategyPositions.addStackLeg(
                                strategyId,
                                com.qkt.positions.PositionLeg(
                                    legId = "$strategyId-$symbol-reconciled-${pos.quantity}",
                                    parentLegId = "$strategyId-$symbol-reconciled-primary",
                                    symbol = symbol,
                                    side = side,
                                    quantity = pos.quantity.abs(),
                                    entryPrice = pos.avgEntryPrice,
                                    openedAt = clock.now(),
                                    role = com.qkt.positions.LegRole.STACK,
                                ),
                            )
                        }
                    }
                    com.qkt.persistence.LegBookReconciler.Outcome.NothingPersisted -> {
                        // Clean state. Nothing to do.
                    }
                }
            }
        }
    }

    /** Captures the broker instances built by [buildBroker] so [buildInstrumentRegistry] can wrap MT5 brokers. */
    private val builtBrokers: MutableList<Broker> = mutableListOf()

    private fun buildBroker(
        paperBroker: PaperBroker,
        bus: EventBus,
        clock: Clock,
        priceTracker: MarketPriceTracker,
    ): Broker {
        if (brokerFactories.isEmpty()) return paperBroker
        val dslStrategies =
            strategies.mapNotNull { (_, s) -> s as? com.qkt.dsl.compile.DslCompiledStrategy }
        val brokerSymbols = mutableMapOf<String, MutableSet<String>>()
        for (s in dslStrategies) {
            for (key in s.declaredStreams.values) {
                brokerSymbols
                    .getOrPut(key.broker.lowercase()) { mutableSetOf() }
                    .add(key.qktSymbol)
            }
        }
        if (brokerSymbols.isEmpty()) return paperBroker
        val routes =
            brokerSymbols.map { (label, syms) ->
                val factory = brokerFactories[label]
                val instance = factory?.invoke(bus, clock, priceTracker) ?: paperBroker
                builtBrokers.add(instance)
                com.qkt.marketdata.source.SymbolPattern
                    .exactSet(syms.toSet()) to instance
            }
        return CompositeBroker(routes = routes, fallback = paperBroker, bus = bus)
    }

    /**
     * Build the [InstrumentRegistry] the trading pipeline uses for SIZING RISK and PaperBroker
     * fill PnL. If an [com.qkt.broker.mt5.MT5Broker] is in the route list, wrap it in
     * [com.qkt.instrument.MT5InstrumentRegistry]; otherwise return [com.qkt.instrument.NoopInstrumentRegistry]
     * so strategies that don't need contract-size-aware math keep working.
     */
    private fun buildInstrumentRegistry(): com.qkt.instrument.InstrumentRegistry {
        val mt5 = builtBrokers.firstOrNull { it is com.qkt.broker.mt5.MT5Broker } as? com.qkt.broker.mt5.MT5Broker
        return if (mt5 != null) {
            com.qkt.instrument.MT5InstrumentRegistry(mt5)
        } else {
            com.qkt.instrument.NoopInstrumentRegistry
        }
    }

    fun start(): LiveSessionHandle {
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker(persistor)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val paperBroker = PaperBroker(bus, clock, priceTracker)
        val broker: Broker = buildBroker(paperBroker, bus, clock, priceTracker)

        // Reconcile persisted leg state against broker positions BEFORE the engine starts
        // taking ticks. Refuses to start on mismatch unless ignoreMismatches=true.
        reconcileOrPreload(strategyPositions, broker)

        val engine = Engine(bus, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        val riskEngine = RiskEngine(rules, haltRules, positions, riskState)

        val trades: MutableList<Trade> = CopyOnWriteArrayList()

        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = ids,
                sequencer = sequencer,
                priceTracker = priceTracker,
                positions = positions,
                pnl = pnl,
                strategyPositions = strategyPositions,
                strategyPnL = strategyPnL,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = strategies,
                riskEngine = riskEngine,
                riskState = riskState,
                mode = Mode.LIVE,
                calendar = calendar,
                source = source,
                candleWindow = candleWindow,
                candleHub =
                    candleHub ?: com.qkt.dsl.compile
                        .CandleHub(),
                onFilled = { trade, realized, strategyId ->
                    trades.add(trade)
                    onTrade(trade, realized, strategyId)
                },
                gate = gate,
                persistor = persistor,
                instruments = buildInstrumentRegistry(),
            )

        bus.subscribe<WarmupTickEvent> { e -> onWarmupTick(e.tick) }
        bus.subscribe<SignalEvent> { e -> onSignal(e.signal) }

        val now = Instant.ofEpochMilli(clock.now())
        val effectiveWarmup =
            warmupOverride
                ?: strategies
                    .map { it.second }
                    .filterIsInstance<Warmable>()
                    .maxByOrNull { it.warmup.windowMs(now) }
                    ?.warmup
                ?: WarmupSpec.None
        IndicatorWarmer(source, pipeline).warmup(symbols, effectiveWarmup, now)
        riskState.warmupComplete = true

        val feed = source.liveTicks(symbols)

        val running = AtomicBoolean(true)
        val terminated = CountDownLatch(1)

        val thread =
            Thread({
                if (mdcStrategy != null) org.slf4j.MDC.put("strategy", mdcStrategy)
                try {
                    while (running.get()) {
                        val tick = feed.next() ?: break
                        pipeline.ingest(tick)
                    }
                } catch (e: InterruptedException) {
                    log.info("LiveSession engine thread interrupted")
                    Thread.currentThread().interrupt()
                } finally {
                    runCatching { feed.close() }
                    running.set(false)
                    terminated.countDown()
                    if (mdcStrategy != null) org.slf4j.MDC.remove("strategy")
                }
            }, "qkt-live-engine")
        thread.isDaemon = true
        thread.start()

        return object : LiveSessionHandle {
            override val running: Boolean get() = running.get()

            override val droppedTicks: Long
                get() = if (feed is LiveTickFeed) feed.droppedTicks.get() else 0L

            override fun stop() {
                running.set(false)
                thread.interrupt()
            }

            override fun awaitTermination(timeout: Duration): Boolean =
                terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

            override fun recentTrades(): List<Trade> = trades.toList()

            override fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo> =
                pipeline.orderManager.pendingStackLayerInfos()

            override fun flatten() {
                val strategyId = strategies.firstOrNull()?.first ?: return
                val current = positions.allPositions()
                for ((symbol, pos) in current) {
                    if (pos.quantity.signum() == 0) continue
                    pipeline.orderManager.cancelPendingForSymbol(symbol)
                    val side =
                        if (pos.quantity.signum() > 0) {
                            com.qkt.common.Side.SELL
                        } else {
                            com.qkt.common.Side.BUY
                        }
                    val request =
                        com.qkt.execution.OrderRequest.Market(
                            id = ids.next(),
                            symbol = symbol,
                            side = side,
                            quantity = pos.quantity.abs(),
                            timeInForce = com.qkt.execution.TimeInForce.GTC,
                            timestamp = clock.now(),
                            strategyId = strategyId,
                        )
                    bus.publish(com.qkt.events.OrderEvent(request))
                }
            }
        }
    }
}
