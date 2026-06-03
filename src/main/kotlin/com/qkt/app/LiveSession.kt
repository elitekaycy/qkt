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
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.events.SignalEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.notify.DailyRollingTracker
import com.qkt.notify.EventTranslator
import com.qkt.notify.NoopNotifier
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.notify.StrategySummary
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionProvider
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.PerStreamWarmable
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
    /**
     * Phase 31 — Telegram alert sink. Default [NoopNotifier] keeps existing call sites and
     * tests unaffected. Production daemons construct a single [com.qkt.notify.TelegramNotifier]
     * from [com.qkt.cli.Config.notify] and pass the same instance to every session.
     */
    private val notifier: Notifier = NoopNotifier,
    /** Opt-in event list — empty disables every subscription, even if a real notifier is present. */
    private val notifyEvents: Set<NotifyEventKind> = emptySet(),
    /**
     * Phase 25D: per-strategy risk overrides for the strategy this session hosts.
     * Null means "use only the session-level [rules] and [haltRules]." When set,
     * the corresponding rule is constructed at start-time with this session's
     * [com.qkt.positions.StrategyPositionTracker] and added to the risk engine.
     */
    private val perStrategyMaxDailyLoss: java.math.BigDecimal? = null,
    private val perStrategyMaxPositionSize: java.math.BigDecimal? = null,
    private val perStrategyMaxOpenPositions: Int? = null,
    /**
     * SCHEDULE block heartbeat interval in milliseconds (#77 follow-up). A
     * dedicated daemon thread calls [com.qkt.app.TradingPipeline.scheduleHeartbeat]
     * at this cadence so a strategy's `SCHEDULE AT 09:00 UTC THEN …` still fires
     * even when no ticks arrive during the matching second. Default 1000ms is
     * sub-millisecond cost on modern hardware; tune up if profiling shows otherwise.
     * Backtest doesn't use this — tick replay drives the heartbeat via
     * [com.qkt.app.TradingPipeline.ingest].
     */
    private val scheduleHeartbeatIntervalMs: Long = 1000L,
    /**
     * Starting balance per strategy id, the basis for `ACCOUNT.equity`
     * (equity = starting balance + realized + unrealized). The portfolio deployer
     * supplies a child's allocated capital here (CAPITAL x WEIGHT) so the child sizes
     * off its slice of the book; standalone sessions leave it empty and equity starts
     * at zero. e.g. {"book:hs" -> 60000} -> the hs child's ACCOUNT.equity reads 60000.
     */
    private val startingBalances: Map<String, java.math.BigDecimal> = emptyMap(),
    /**
     * Injectable event bus for tests that need to observe bus events (e.g. [com.qkt.events.RiskEvent]).
     * When `null` (the default), [start] constructs its own bus — the normal production path.
     * e.g. test passes a bus, subscribes to [com.qkt.events.RiskEvent.Halted], calls
     * [LiveSessionHandle.halt], then asserts the event arrived.
     */
    private val busOverride: EventBus? = null,
) {
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    /** Accumulates trades/halts/equity-delta for the daily summary. */
    private val dailyTracker = DailyRollingTracker()

    /**
     * Phase 25B: fetch historical bars per stream and seed the candle hub so
     * lookback (`btc.close[N]`) and Phase 24's WarmupGate are satisfied the
     * moment live ticks start. Throws [WarmupFailedException] on broker error
     * so deploy aborts before any rule fires.
     */
    private fun seedHubFromHistory(
        strategies: List<Pair<String, Strategy>>,
        hub: com.qkt.dsl.compile.CandleHub,
        perStreamSpecs: Map<String, WarmupSpec>,
        now: Instant,
    ) {
        // Map qkt symbol → owning DSL strategy's HubKey (multiple strategies may
        // declare the same symbol on different timeframes; seed each).
        for ((sessionStrategyName, strategy) in strategies) {
            if (strategy !is DslCompiledStrategy) continue
            for ((alias, key) in strategy.declaredStreams) {
                val symbol = key.qktSymbol
                val spec = perStreamSpecs[symbol] ?: continue
                val barSpec =
                    when (spec) {
                        is WarmupSpec.Bars -> spec
                        is WarmupSpec.None -> continue
                        else -> continue // Duration / Ticks handled by IndicatorWarmer; skip seed
                    }
                val upperMs = barSpec.window.windowStartFor(now.toEpochMilli())
                val totalMs = barSpec.window.durationMs * barSpec.count
                val lowerMs = upperMs - totalMs
                val range = TimeRange(Instant.ofEpochMilli(lowerMs), Instant.ofEpochMilli(upperMs))
                val candles =
                    try {
                        source.bars(symbol, barSpec.window, range).toList()
                    } catch (e: Exception) {
                        throw WarmupFailedException(alias, symbol, e)
                    }
                hub.seed(key, candles)
                log.info(
                    "warmup: seeded hub for strategy={} alias={} symbol={} bars={}",
                    sessionStrategyName,
                    alias,
                    symbol,
                    candles.size,
                )
            }
        }
    }

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
        positions: PositionProvider,
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
        // Fail fast if a strategy declares a broker prefix that has no configured factory.
        // Without this check, the old code path silently fell through to `paperBroker` for
        // unmapped prefixes — strategy fills happened on paper instead of the intended venue
        // and operators only noticed when they couldn't find real fills (#139).
        val missing = brokerSymbols.keys.filter { it !in brokerFactories }
        require(missing.isEmpty()) {
            val configuredList = brokerFactories.keys.sorted().joinToString(", ")
            val missingList = missing.sorted().joinToString(", ")
            "Strategy declares broker prefix(es) with no configured factory: [$missingList]. " +
                "Configured brokers: [$configuredList]. " +
                "Either fix the strategy's SYMBOLS prefix or add a `type: mt5` entry " +
                "in qkt.config.yaml's brokers block for each missing prefix."
        }
        // Single-strategy sessions (daemon path) propagate the strategy name so MT5 brokers
        // can correlate orphan recovery; multi-strategy sessions (LiveDemo, Main) pass null.
        val owningStrategy = strategies.singleOrNull()?.first
        val routes =
            brokerSymbols.map { (label, syms) ->
                val factory = brokerFactories.getValue(label)
                val instance = factory.invoke(bus, clock, priceTracker, positions, owningStrategy)
                builtBrokers.add(instance)
                com.qkt.marketdata.source.SymbolPattern
                    .exactSet(syms.toSet()) to instance
            }
        return CompositeBroker(routes = routes, fallback = paperBroker, bus = bus)
    }

    /**
     * Build the [com.qkt.instrument.InstrumentRegistry] the trading pipeline uses for SIZING
     * RISK and PaperBroker fill PnL. Wraps every [com.qkt.broker.mt5.MT5Broker] in the
     * route list via [com.qkt.instrument.MultiMT5InstrumentRegistry] so multi-MT5 deployments
     * (#139) get the correct contract specs for each broker's symbols. Falls back to
     * [com.qkt.instrument.NoopInstrumentRegistry] when no MT5 broker is configured so
     * paper-only strategies that don't need contract-size-aware math keep working.
     */
    private fun buildInstrumentRegistry(): com.qkt.instrument.InstrumentRegistry {
        val mt5Registries =
            builtBrokers
                .filterIsInstance<com.qkt.broker.mt5.MT5Broker>()
                .map { com.qkt.instrument.MT5InstrumentRegistry(it) }
        return if (mt5Registries.isEmpty()) {
            com.qkt.instrument.NoopInstrumentRegistry
        } else {
            com.qkt.instrument.MultiMT5InstrumentRegistry(mt5Registries)
        }
    }

    /**
     * Subscribe notifier handlers for the bus-driven event kinds in [notifyEvents]. Must be
     * called after [bus] is constructed and before any publish — handlers registered after a
     * publish miss that event silently.
     *
     * Each handler is wrapped in [runCatching] so a notifier fault never propagates back into
     * the bus dispatch loop, whose semantics prevent later handlers from running if any handler
     * throws.
     *
     * [BrokerEvent.OrderRejected] omits symbol/side/quantity; [orderManager] recovers them
     * via [OrderManager.orderDetailsFor]. Not wired here: [NotificationEvent.DaemonStarted]
     * is a daemon-level concern fired by [com.qkt.cli.DaemonCommand];
     * [NotificationEvent.StrategyError] has no bus source yet.
     */
    private fun wireNotifierSubscriptions(
        bus: EventBus,
        orderManager: OrderManager,
    ) {
        if (NotifyEventKind.HALTED in notifyEvents) {
            bus.subscribe<RiskEvent.Halted> { ev ->
                runCatching { notifier.notify(EventTranslator.fromRiskHalted(ev)) }
                    .onFailure { t -> log.warn("[notify] handler failed for Halted", t) }
            }
        }
        if (NotifyEventKind.RESUMED in notifyEvents) {
            bus.subscribe<RiskEvent.Resumed> { ev ->
                runCatching { notifier.notify(EventTranslator.fromRiskResumed(ev)) }
                    .onFailure { t -> log.warn("[notify] handler failed for Resumed", t) }
            }
        }
        if (NotifyEventKind.POSITION_RECONCILED in notifyEvents) {
            // Best-effort strategyId: this session typically hosts one strategy. If multiple
            // are present, use the first; the alert still names the symbol so the operator
            // can disambiguate from logs.
            val ownerStrategyId = strategies.firstOrNull()?.first.orEmpty()
            bus.subscribe<BrokerEvent.PositionReconciled> { ev ->
                runCatching {
                    notifier.notify(
                        EventTranslator.fromPositionReconciled(event = ev, strategyId = ownerStrategyId),
                    )
                }.onFailure { t -> log.warn("[notify] handler failed for PositionReconciled", t) }
            }
        }
        if (NotifyEventKind.ORDER_REJECTED in notifyEvents) {
            bus.subscribe<BrokerEvent.OrderRejected> { ev ->
                runCatching {
                    val details = orderManager.orderDetailsFor(ev.clientOrderId)
                    if (details != null) {
                        notifier.notify(
                            EventTranslator.fromBrokerRejected(
                                event = ev,
                                symbol = details.symbol,
                                side = details.side,
                                quantity = details.quantity,
                            ),
                        )
                    } else {
                        log.warn("[notify] OrderRejected for unknown order {} — skipping alert", ev.clientOrderId)
                    }
                }.onFailure { t -> log.warn("[notify] handler failed for OrderRejected", t) }
            }
        }
    }

    /**
     * The per-strategy daily-summary rows for this session — equity, P&L, positions, and
     * the [dailyTracker] window totals. The daemon owns one [DailySummaryScheduler] across
     * every session; its producer calls this once per fire. Reading the rows snapshots and
     * resets the tracker, so it must be called exactly once per summary.
     */
    private fun dailySummaryRows(
        strategyPnL: StrategyPnL,
        strategyPositions: StrategyPositionTracker,
    ): List<StrategySummary> =
        strategies.map { (strategyId, _) ->
            val positions = strategyPositions.positionsFor(strategyId)
            val summary =
                if (positions.isEmpty() ||
                    positions.values.all { it.quantity.signum() == 0 }
                ) {
                    "flat"
                } else {
                    positions.entries.joinToString(", ") { (sym, p) ->
                        "${if (p.quantity.signum() > 0) "long" else "short"} ${p.quantity.abs().toPlainString()} $sym"
                    }
                }
            val equity = strategyPnL.equityFor(strategyId)
            val totals = dailyTracker.snapshot(strategyId, equity)
            StrategySummary(
                strategyId = strategyId,
                equity = equity,
                equityDeltaPct = totals.equityDeltaPct,
                realizedToday = strategyPnL.realizedFor(strategyId),
                unrealized = strategyPnL.unrealizedTotalFor(strategyId),
                tradesToday = totals.tradesToday,
                haltsToday = totals.haltsToday,
                positionsSummary = summary,
            )
        }

    fun start(): LiveSessionHandle {
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val strategyPositions = StrategyPositionTracker(persistor)
        val bus = busOverride ?: EventBus(clock, sequencer)
        val paperBroker = PaperBroker(bus, clock, priceTracker)
        val broker: Broker = buildBroker(paperBroker, bus, clock, priceTracker, positions)
        // Phase 30: registry must be built after the brokers so [MT5InstrumentRegistry]
        // can wrap the [com.qkt.broker.mt5.MT5Broker] instance if one was constructed.
        val instruments = buildInstrumentRegistry()
        val pnl = PnLCalculator(positions, priceTracker, instruments)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker, instruments)
        startingBalances.forEach { (id, balance) -> strategyPnL.setStartingBalance(id, balance) }

        // Reconcile persisted leg state against broker positions BEFORE the engine starts
        // taking ticks. Refuses to start on mismatch unless ignoreMismatches=true.
        reconcileOrPreload(strategyPositions, broker)

        val engine = Engine(bus, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, clock, bus)

        // Phase 25D: per-strategy risk overrides for the (single) strategy in this session.
        // The daemon creates one LiveSession per deployed strategy, so the first entry is
        // the only one. If the caller didn't set per-strategy caps, these stay empty.
        val riskOwnerStrategyId = strategies.firstOrNull()?.first
        val perStrategyHaltRules = mutableListOf<com.qkt.risk.HaltRule>()
        val perStrategyRiskRules = mutableListOf<com.qkt.risk.RiskRule>()
        if (riskOwnerStrategyId != null) {
            perStrategyMaxDailyLoss?.let {
                perStrategyHaltRules.add(
                    com.qkt.risk.rules
                        .MaxStrategyDailyLoss(riskOwnerStrategyId, it),
                )
            }
            perStrategyMaxPositionSize?.let {
                perStrategyRiskRules.add(
                    com.qkt.risk.rules
                        .MaxStrategyPositionSize(riskOwnerStrategyId, it, strategyPositions),
                )
            }
            perStrategyMaxOpenPositions?.let {
                perStrategyRiskRules.add(
                    com.qkt.risk.rules
                        .MaxStrategyOpenPositions(riskOwnerStrategyId, it, strategyPositions),
                )
            }
        }
        val riskEngine =
            RiskEngine(
                rules + perStrategyRiskRules,
                haltRules + perStrategyHaltRules,
                positions,
                riskState,
            )

        val trades: MutableList<Trade> = CopyOnWriteArrayList()

        val pipelineCandleHub =
            candleHub ?: com.qkt.dsl.compile
                .CandleHub()

        // Resolver for `SCHEDULE … BROKER`: take the first MT5 broker in this
        // session's route list and use its profile's `serverTzOffsetHours`.
        // LiveSession is per-strategy in the daemon model, so all calls return
        // the same zone — strategy id is ignored. Null when no MT5 broker is
        // in play (paper-only / Bybit-only sessions).
        val brokerZoneIdFor: ((String) -> java.time.ZoneId?)? =
            run {
                val mt5 = builtBrokers.filterIsInstance<com.qkt.broker.mt5.MT5Broker>().firstOrNull()
                if (mt5 != null) {
                    val zone: java.time.ZoneId =
                        java.time.ZoneOffset.ofHours(mt5.profile.serverTzOffsetHours)
                    ({ _: String -> zone })
                } else {
                    null
                }
            }

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
                candleHub = pipelineCandleHub,
                onFilled = { trade, realized, strategyId ->
                    trades.add(trade)
                    dailyTracker.recordTrade(strategyId)
                    onTrade(trade, realized, strategyId)
                },
                gate = gate,
                persistor = persistor,
                instruments = instruments,
                brokerZoneIdFor = brokerZoneIdFor,
            )

        bus.subscribe<WarmupTickEvent> { e -> onWarmupTick(e.tick) }
        bus.subscribe<SignalEvent> { e -> onSignal(e.signal) }

        // Register notifier handlers before the warmup phase so a warmup-time risk halt
        // (rare but possible) reaches Telegram. Bus dispatch is single-threaded and synchronous,
        // so any publish that happens after this line will see the new subscribers.
        wireNotifierSubscriptions(bus, pipeline.orderManager)
        // Restore OCO legs from the persistor and reconcile them against venue truth so
        // any sibling whose pair filled during downtime is cancelled before ticks flow.
        pipeline.orderManager.restore(strategies.map { it.first })
        // Keep the daily-summary tracker's halt count current. The daemon owns the one
        // DailySummaryScheduler; this session just feeds its tracker.
        val ownerStrategyId = strategies.firstOrNull()?.first.orEmpty()
        bus.subscribe<RiskEvent.Halted> { ev ->
            dailyTracker.recordHalt(ev.strategyId ?: ownerStrategyId)
        }

        val now = Instant.ofEpochMilli(clock.now())

        // Phase 25B: per-stream pre-fetch + hub seeding for DSL strategies.
        // Seeds candle history (so lookback works on the first live bar) and triggers
        // indicator warmup via the existing IndicatorWarmer pipeline. Fail-fast: any
        // broker error here aborts deploy with a typed exception.
        val perStreamSpecs: Map<String, WarmupSpec> =
            strategies
                .map { it.second }
                .filterIsInstance<PerStreamWarmable>()
                .flatMap { it.perStreamWarmup.entries }
                .associate { it.key to it.value }
        if (perStreamSpecs.isNotEmpty()) {
            seedHubFromHistory(strategies, pipelineCandleHub, perStreamSpecs, now)
            IndicatorWarmer(source, pipeline).warmup(perStreamSpecs, now)
        } else {
            val effectiveWarmup =
                warmupOverride
                    ?: strategies
                        .map { it.second }
                        .filterIsInstance<Warmable>()
                        .maxByOrNull { it.warmup.windowMs(now) }
                        ?.warmup
                    ?: WarmupSpec.None
            IndicatorWarmer(source, pipeline).warmup(symbols, effectiveWarmup, now)
        }
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

        // Quiet-market heartbeat (#77 Phase 40 follow-up). Without this, SCHEDULE
        // fires only happen when ticks arrive — a 19:55 UTC placement would slip
        // by seconds on a quiet Asia session. 1Hz is configurable via
        // [scheduleHeartbeatIntervalMs]; default 1s is sub-millisecond cost on
        // modern hardware.
        val scheduleHeartbeat: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor { r ->
                    Thread(r, "qkt-schedule-heartbeat").apply { isDaemon = true }
                }
        scheduleHeartbeat.scheduleAtFixedRate(
            { runCatching { pipeline.scheduleHeartbeat(clock.now()) } },
            scheduleHeartbeatIntervalMs,
            scheduleHeartbeatIntervalMs,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )

        // Fire StrategyStarted per strategy this session hosts. Lifecycle events bypass the
        // bus because no other engine component consumes them.
        if (NotifyEventKind.STRATEGY_STARTED in notifyEvents) {
            for ((strategyId, _) in strategies) {
                runCatching {
                    notifier.notify(
                        NotificationEvent.StrategyStarted(
                            strategyId = strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                }.onFailure { t -> log.warn("[notify] StrategyStarted fire failed", t) }
            }
        }

        return object : LiveSessionHandle {
            override val running: Boolean get() = running.get()

            override val droppedTicks: Long
                get() = if (feed is LiveTickFeed) feed.droppedTicks.get() else 0L

            override fun stop() {
                running.set(false)
                thread.interrupt()
                // Stop the schedule heartbeat thread so it doesn't outlive the session.
                runCatching {
                    scheduleHeartbeat.shutdownNow()
                    scheduleHeartbeat.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                // Release venue-side lifecycle resources (MT5 pollers, Bybit reconcilers)
                // so a long-running daemon cycling strategies doesn't accumulate threads.
                for (b in builtBrokers) runCatching { b.shutdown() }
                // Drop hub registrations attributed to this session's strategies so
                // their aggregators and listener closures fall out of scope.
                for ((strategyId, _) in strategies) {
                    runCatching { pipelineCandleHub.unregister(strategyId) }
                }
                if (NotifyEventKind.STRATEGY_STOPPED in notifyEvents) {
                    for ((strategyId, _) in strategies) {
                        runCatching {
                            notifier.notify(
                                NotificationEvent.StrategyStopped(
                                    strategyId = strategyId,
                                    flatten = false,
                                    timestamp = clock.now(),
                                ),
                            )
                        }.onFailure { t -> log.warn("[notify] StrategyStopped fire failed", t) }
                    }
                }
            }

            override fun awaitTermination(timeout: Duration): Boolean =
                terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

            override fun recentTrades(): List<Trade> = trades.toList()

            override fun dailySummaryRows(): List<StrategySummary> =
                this@LiveSession.dailySummaryRows(strategyPnL, strategyPositions)

            override fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo> =
                pipeline.orderManager.pendingStackLayerInfos()

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

            override fun halt(reason: String) {
                riskState.halt(reason)
            }

            override fun resume() {
                riskState.resume()
            }

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
