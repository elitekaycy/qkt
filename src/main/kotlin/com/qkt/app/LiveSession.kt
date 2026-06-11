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
    private val perStrategyMaxDrawdownPct: java.math.BigDecimal? = null,
    private val perStrategyMaxDailyDrawdownPct: java.math.BigDecimal? = null,
    /**
     * Account starting balance — the basis for static total drawdown and the daily-drawdown
     * reference. Prop-firm limits measure against this. Defaults to zero (drawdown halts inert).
     */
    private val initialBalance: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    private val totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
    private val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
    /** Mandatory pre-trade caps (#393); defaults from [com.qkt.risk.rules.PreTradeControls]. */
    private val maxOrderQty: java.math.BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
    private val maxOrderNotional: java.math.BigDecimal =
        com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
    private val priceCollarFrac: java.math.BigDecimal =
        com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
    /** Runaway breaker thresholds (#396); zero disables a counter. */
    private val runawayMaxRoundTrips: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS,
    private val runawayMaxRejections: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS,
    /**
     * Pre-entry margin floor in percent (#398); entries reject while the venue margin
     * level is below it. Zero disables. Default 200 = keep 2x coverage, the
     * practitioner norm against MT5's ~50% stop-out.
     */
    private val marginFloorPct: java.math.BigDecimal = java.math.BigDecimal("200"),
    /**
     * Measured-usage window (#399): hours after start during which entries above
     * [measuredUsageMaxQty] reject. Zero disables (the default here — the daemon path
     * turns it on; embedded/test sessions opt in).
     */
    private val measuredUsageHours: Long = 0L,
    private val measuredUsageMaxQty: java.math.BigDecimal =
        com.qkt.risk.rules.MeasuredUsage.DEFAULT_MEASURED_MAX_QTY,
    /** Append-only order-event journal (#400); null disables (tests, backtest replays). */
    private val journal: com.qkt.observe.OrderJournal? = null,
    /**
     * Best-effort egress to a qkt-insights collector; null disables (the default).
     * The daemon constructs one shared [com.qkt.observe.insights.InsightsSink] from
     * [com.qkt.cli.Config.insights] and passes the same instance to every session.
     * The engine thread only enqueues; the sink's own thread does JSON + HTTP.
     */
    private val insightsSink: com.qkt.observe.insights.InsightsSink? = null,
    /** Event families to stream; empty wires nothing even when a sink is present. */
    private val insightsEvents: Set<com.qkt.observe.insights.InsightsEventFamily> = emptySet(),
    /** Cadence for engine-thread equity/position snapshots flowing through the sink. */
    private val insightsSnapshotIntervalMs: Long = 5_000L,
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
    /** Base backoff between reconcile read attempts; tests shrink it to keep retries fast. */
    private val reconcileReadBackoffMs: Long = 500L,
) {
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    private companion object {
        /** Attempts to read broker positions at reconcile before refusing to start. */
        const val RECONCILE_READ_ATTEMPTS: Int = 5

        /**
         * Bound on buffered ticks awaiting the engine thread. At a heavy 100 ticks/sec
         * this is ~100s of backlog — far past the point where shedding the oldest tick
         * is strictly better than growing the heap.
         */
        const val TICK_QUEUE_CAPACITY: Int = 10_000

        /** Tick-queue poll timeout — bounds the control-queue re-check latency. */
        const val QUEUE_POLL_MS: Long = 25L
    }

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
        // Never reconcile against assumed state: a transient broker error that reads as
        // "no open positions" lets the session start flat while holding leveraged
        // positions. Retry with backoff; refuse to start without one clean read.
        var brokerByQktSymbol: Map<String, List<com.qkt.positions.Position>>? = null
        var lastReadError: Throwable? = null
        for (attempt in 1..RECONCILE_READ_ATTEMPTS) {
            val read = runCatching { broker.getOpenPositions() }
            val positions = read.getOrNull()
            if (positions != null) {
                brokerByQktSymbol = positions
                break
            }
            lastReadError = read.exceptionOrNull()
            log.warn(
                "reconcile: broker position read failed (attempt {}/{}): {}",
                attempt,
                RECONCILE_READ_ATTEMPTS,
                lastReadError?.message,
            )
            if (attempt < RECONCILE_READ_ATTEMPTS) Thread.sleep(reconcileReadBackoffMs * attempt)
        }
        if (brokerByQktSymbol == null) {
            throw ReconcileException(
                "broker position read failed $RECONCILE_READ_ATTEMPTS times — refusing to start " +
                    "on assumed state. Last error: ${lastReadError?.message}",
            )
        }
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

    /** Every order-lifecycle event lands in the append-only journal, in bus order. */
    private fun wireJournal(
        bus: EventBus,
        journal: com.qkt.observe.OrderJournal,
    ) {
        bus.subscribe<com.qkt.events.OrderEvent> { e ->
            journal.append(
                e.request.strategyId,
                "submit",
                mapOf(
                    "id" to e.request.id,
                    "type" to e.request::class.simpleName,
                    "symbol" to e.request.symbol,
                    "side" to e.request.side.name,
                    "qty" to e.request.quantity.toPlainString(),
                ),
            )
        }
        bus.subscribe<BrokerEvent.OrderAccepted> { e ->
            journal.append(e.strategyId, "accepted", mapOf("id" to e.clientOrderId, "broker" to e.brokerOrderId))
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            journal.append(
                e.strategyId,
                "rejected",
                mapOf("id" to e.clientOrderId, "reason" to e.reason),
            )
        }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            journal.append(
                e.strategyId,
                "filled",
                mapOf(
                    "id" to e.clientOrderId,
                    "broker" to e.brokerOrderId,
                    "symbol" to e.symbol,
                    "side" to e.side.name,
                    "price" to e.price.toPlainString(),
                    "qty" to e.quantity.toPlainString(),
                    "venueCosts" to e.venueCosts.toPlainString(),
                ),
            )
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { e ->
            journal.append(
                e.strategyId,
                "cancelled",
                mapOf("id" to e.clientOrderId, "reason" to e.reason),
            )
        }
        bus.subscribe<com.qkt.events.RiskRejectedEvent> { e ->
            journal.append(
                e.request.strategyId,
                "risk-rejected",
                mapOf("id" to e.request.id, "symbol" to e.request.symbol, "reason" to e.reason),
            )
        }
        bus.subscribe<RiskEvent.Halted> { e ->
            journal.append(e.strategyId.orEmpty(), "halted", mapOf("reason" to e.reason))
        }
        bus.subscribe<RiskEvent.Resumed> { e ->
            journal.append(e.strategyId.orEmpty(), "resumed", emptyMap<String, String?>())
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
        if (NotifyEventKind.STRATEGY_ERROR in notifyEvents) {
            val ownerForError = strategies.firstOrNull()?.first.orEmpty()
            bus.subscribe<BrokerEvent.GatewayUnreachable> { ev ->
                runCatching {
                    notifier.notify(
                        NotificationEvent.StrategyError(
                            strategyId = ownerForError,
                            message =
                                "MT5 gateway '${ev.broker}' unreachable for ${ev.consecutiveFailures} " +
                                    "consecutive polls — position/pending reconciliation suspended",
                            timestamp = ev.timestamp,
                        ),
                    )
                }.onFailure { t -> log.warn("[notify] handler failed for GatewayUnreachable", t) }
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
     * Streams allow-listed event families to the insights sink. Each handler only builds
     * a small envelope and enqueues it — the sink's own thread does JSON and HTTP, so
     * none of this touches the engine loop's latency. Mirrors [wireJournal]'s shape.
     */
    private fun wireInsights(
        bus: EventBus,
        sink: com.qkt.observe.insights.InsightsSink,
    ) {
        val t = com.qkt.observe.insights.InsightsTranslate
        if (com.qkt.observe.insights.InsightsEventFamily.SIGNAL in insightsEvents) {
            bus.subscribe<SignalEvent> { e -> t.fromSignal(e)?.let(sink::offer) }
        }
        if (com.qkt.observe.insights.InsightsEventFamily.ORDER in insightsEvents) {
            bus.subscribe<com.qkt.events.OrderEvent> { e -> sink.offer(t.fromOrderSubmit(e)) }
            bus.subscribe<BrokerEvent.OrderAccepted> { e -> sink.offer(t.fromOrderAccepted(e)) }
            bus.subscribe<BrokerEvent.OrderFilled> { e -> sink.offer(t.fromOrderFilled(e)) }
            bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> sink.offer(t.fromOrderPartiallyFilled(e)) }
            bus.subscribe<BrokerEvent.OrderCancelled> { e -> sink.offer(t.fromOrderCancelled(e)) }
            bus.subscribe<BrokerEvent.OrderRejected> { e -> sink.offer(t.fromOrderRejected(e)) }
            bus.subscribe<BrokerEvent.OrderModified> { e -> sink.offer(t.fromOrderModified(e)) }
        }
        if (com.qkt.observe.insights.InsightsEventFamily.TRADE in insightsEvents) {
            bus.subscribe<com.qkt.events.TradeEvent> { e -> sink.offer(t.fromTrade(e)) }
        }
        if (com.qkt.observe.insights.InsightsEventFamily.RISK in insightsEvents) {
            bus.subscribe<com.qkt.events.RiskRejectedEvent> { e -> sink.offer(t.fromRiskRejected(e)) }
            bus.subscribe<RiskEvent.Halted> { e -> sink.offer(t.fromRiskHalted(e)) }
            bus.subscribe<RiskEvent.Resumed> { e -> sink.offer(t.fromRiskResumed(e)) }
        }
        if (com.qkt.observe.insights.InsightsEventFamily.POSITION in insightsEvents) {
            bus.subscribe<BrokerEvent.PositionReconciled> { e -> sink.offer(t.fromPositionReconciled(e)) }
            bus.subscribe<BrokerEvent.BalancesUpdated> { e -> sink.offer(t.fromBalancesUpdated(e)) }
            bus.subscribe<BrokerEvent.GatewayUnreachable> { e -> sink.offer(t.fromGatewayUnreachable(e)) }
        }
    }

    /**
     * Emits per-strategy equity and position snapshots to the insights sink. Must run on
     * the engine thread — [StrategyPnL] and [StrategyPositionTracker] are engine-thread-only.
     * Called from the heartbeat branch of the engine loop on the configured cadence and
     * after fills, so dashboards see fresh unrealized PnL without per-tick noise.
     */
    private fun emitInsightsSnapshots(
        nowMs: Long,
        strategyPnL: StrategyPnL,
        strategyPositions: StrategyPositionTracker,
    ) {
        val sink = insightsSink ?: return
        val t = com.qkt.observe.insights.InsightsTranslate
        for ((strategyId, _) in strategies) {
            sink.offer(
                t.equitySnapshot(
                    ts = nowMs,
                    strategyId = strategyId,
                    realized = strategyPnL.realizedFor(strategyId),
                    unrealized = strategyPnL.unrealizedTotalFor(strategyId),
                    equity = strategyPnL.equityFor(strategyId),
                    startingBalance = strategyPnL.startingBalanceFor(strategyId),
                ),
            )
            for ((symbol, _) in strategyPositions.positionsFor(strategyId)) {
                val legs = strategyPositions.legBookFor(strategyId, symbol)?.all() ?: continue
                sink.offer(t.positionSnapshot(ts = nowMs, strategyId = strategyId, symbol = symbol, legs = legs))
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
        // No quote-currency conversion exists: refuse symbols whose PnL would book at
        // the wrong magnitude (e.g. USDJPY's JPY PnL recorded as USD, ~150x off).
        com.qkt.instrument.QuoteCurrencyGuard
            .assertAccountQuoted(symbols)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val strategyPositions = StrategyPositionTracker(persistor)
        val bus = busOverride ?: EventBus(clock, sequencer)
        // The control queue and bus sink exist BEFORE any broker constructs: MT5
        // pollers start at construction and publish from their own threads — without
        // the sink those events dispatch inline against a half-built pipeline (#388).
        // They queue here and drain, in order, once the engine loop starts.
        val running = AtomicBoolean(true)
        val control = java.util.concurrent.LinkedBlockingQueue<Inbound>()
        bus.bindSink { ev -> if (running.get()) control.put(Inbound.BusEvent(ev)) }
        val paperBroker = PaperBroker(bus, clock, priceTracker)
        val broker: Broker = buildBroker(paperBroker, bus, clock, priceTracker, positions)
        // Phase 30: registry must be built after the brokers so [MT5InstrumentRegistry]
        // can wrap the [com.qkt.broker.mt5.MT5Broker] instance if one was constructed.
        val instruments = buildInstrumentRegistry()
        val pnl = PnLCalculator(positions, priceTracker, instruments)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker, instruments)
        // Every deploy path needs a starting balance: portfolio deploys pass per-strategy
        // entries in [startingBalances]; standalone deploys fall back to the session-level
        // [initialBalance] so ACCOUNT.equity and % OF EQUITY sizing don't run on zero.
        for ((id, _) in strategies) {
            val balance = startingBalances[id] ?: initialBalance
            if (balance.signum() > 0) strategyPnL.setStartingBalance(id, balance)
        }

        // PnL books `contractSize` per symbol; on a real registry an unresolvable symbol
        // must fail HERE at deploy, not silently book 1.0 at fill time (100-100,000x off
        // for metals/FX). NoopInstrumentRegistry stays exempt — it is the explicit
        // unit-contract default for paper/crypto paths.
        if (instruments !is com.qkt.instrument.NoopInstrumentRegistry) {
            for (symbol in symbols) {
                if (!com.qkt.instrument.QuoteCurrencyGuard
                        .requiresContractSizeMeta(symbol)
                ) {
                    continue
                }
                requireNotNull(instruments.lookup(symbol)) {
                    "InstrumentMeta unresolvable for $symbol at deploy — refusing to start " +
                        "(PnL would silently book contractSize=1)"
                }
            }
        }

        // Reconcile persisted leg state against broker positions BEFORE the engine starts
        // taking ticks. Refuses to start on mismatch unless ignoreMismatches=true.
        reconcileOrPreload(strategyPositions, broker)

        val engine = Engine(bus, priceTracker)
        val riskPersistId = strategies.firstOrNull()?.first ?: "session"
        val riskState =
            RiskState(
                pnl,
                strategyPnL,
                clock,
                bus,
                initialBalance,
                dailyDdBasis,
                persist = { snap ->
                    runCatching { persistor.saveRiskState(riskPersistId, snap) }
                        .onFailure { e -> log.warn("risk-state persist failed: ${e.message}") }
                },
            )
        // Restore halts + the day's realized PnL: a restart must not un-halt a halted
        // strategy or hand it a fresh daily budget the same day it exhausted one.
        persistor.loadRiskState(riskPersistId)?.let { persisted ->
            riskState.restore(persisted)
            if (riskState.halted) {
                log.warn("restored HALTED risk state for {}: {}", riskPersistId, riskState.haltReason)
            }
        }

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
            val ownerInitialBalance = startingBalances[riskOwnerStrategyId] ?: initialBalance
            perStrategyMaxDrawdownPct?.let {
                perStrategyHaltRules.add(
                    com.qkt.risk.rules
                        .MaxStrategyDrawdown(riskOwnerStrategyId, it, totalDdBasis, ownerInitialBalance),
                )
            }
            perStrategyMaxDailyDrawdownPct?.let {
                perStrategyHaltRules.add(
                    com.qkt.risk.rules
                        .MaxStrategyDailyDrawdown(riskOwnerStrategyId, it),
                )
            }
        }
        // Mandatory pre-trade controls are always on — they ship with defaults so "no
        // limit configured" can never mean "no limit" (#393).
        val preTradeRules =
            com.qkt.risk.rules.PreTradeControls.standard(
                prices = priceTracker,
                instruments = instruments,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                priceCollarFrac = priceCollarFrac,
            )
        // Stale/outlier judgment over the live feeds (#395): suppresses NEW orders on
        // frozen data and drops implausible ticks before they poison indicators.
        val marketDataGate = com.qkt.marketdata.MarketDataGate(clock)
        val marginRules =
            if (marginFloorPct.signum() > 0) {
                listOf(
                    com.qkt.risk.rules
                        .MarginFloor(broker, marginFloorPct),
                )
            } else {
                emptyList()
            }
        val measuredRules =
            if (measuredUsageHours > 0L) {
                log.warn(
                    "measured-usage window active for {}h: entries above {} reject " +
                        "(risk.measured_usage_hours: 0 opts out)",
                    measuredUsageHours,
                    measuredUsageMaxQty.toPlainString(),
                )
                listOf(
                    com.qkt.risk.rules.MeasuredUsage(
                        clock = clock,
                        startedAtMs = clock.now(),
                        windowHours = measuredUsageHours,
                        maxQty = measuredUsageMaxQty,
                    ),
                )
            } else {
                emptyList()
            }
        val riskEngine =
            RiskEngine(
                rules + perStrategyRiskRules + preTradeRules + marginRules + measuredRules +
                    com.qkt.marketdata.MarketDataHealthRule(marketDataGate),
                haltRules + perStrategyHaltRules,
                positions,
                riskState,
            )

        val trades: MutableList<Trade> = CopyOnWriteArrayList()

        val pipelineCandleHub =
            candleHub ?: com.qkt.dsl.compile
                .CandleHub()

        val now = Instant.ofEpochMilli(clock.now())

        // Phase 25B: per-stream pre-fetch + hub seeding for DSL strategies. Seeding
        // must happen BEFORE TradingPipeline binds strategies to the hub: bindToHub
        // credits the WarmupGate from hub.historySize, so seeding afterwards leaves
        // the gate cold and every deploy waits out a full live warmup window on
        // already-warm indicators. register() is idempotent — the pipeline's later
        // registration extends these slots rather than replacing them. Retention is
        // widened to the warmup bar count so the seeded history survives the ring.
        // Fail-fast: any broker error here aborts deploy with a typed exception.
        val perStreamSpecs: Map<String, WarmupSpec> =
            strategies
                .map { it.second }
                .filterIsInstance<PerStreamWarmable>()
                .flatMap { it.perStreamWarmup.entries }
                .associate { it.key to it.value }
        if (perStreamSpecs.isNotEmpty()) {
            for ((strategyId, strategy) in strategies) {
                if (strategy !is DslCompiledStrategy) continue
                for ((key, retention) in strategy.retentionByKey) {
                    val warmupBars = (perStreamSpecs[key.qktSymbol] as? WarmupSpec.Bars)?.count ?: 0
                    pipelineCandleHub.register(key, maxOf(retention, warmupBars), strategyId)
                }
            }
            seedHubFromHistory(strategies, pipelineCandleHub, perStreamSpecs, now)
        }

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
                marketDataGate = marketDataGate,
                runawayBreaker =
                    com.qkt.risk.RunawayBreaker(
                        clock = clock,
                        riskState = riskState,
                        maxRoundTrips = runawayMaxRoundTrips,
                        maxRejections = runawayMaxRejections,
                    ),
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
        journal?.let { wireJournal(bus, it) }

        // Register notifier handlers before the warmup phase so a warmup-time risk halt
        // (rare but possible) reaches Telegram. Bus dispatch is single-threaded and synchronous,
        // so any publish that happens after this line will see the new subscribers.
        wireNotifierSubscriptions(bus, pipeline.orderManager)
        insightsSink?.let { sink ->
            wireInsights(bus, sink)
            // Fills change equity; snapshot right away so dashboards don't wait a full
            // heartbeat interval. Registered after the pipeline's bookkeeping subscribers,
            // so the PnL read here already includes the fill.
            if (com.qkt.observe.insights.InsightsEventFamily.SNAPSHOT in insightsEvents) {
                bus.subscribe<com.qkt.events.TradeEvent> { e ->
                    emitInsightsSnapshots(e.timestamp, strategyPnL, strategyPositions)
                }
            }
        }
        // Restore OCO legs from the persistor and reconcile them against venue truth so
        // any sibling whose pair filled during downtime is cancelled before ticks flow.
        pipeline.orderManager.restore(strategies.map { it.first })
        // Keep the daily-summary tracker's halt count current. The daemon owns the one
        // DailySummaryScheduler; this session just feeds its tracker.
        val ownerStrategyId = strategies.firstOrNull()?.first.orEmpty()
        bus.subscribe<RiskEvent.Halted> { ev ->
            dailyTracker.recordHalt(ev.strategyId ?: ownerStrategyId)
        }
        // A halt is only a kill switch if it also takes down what is RESTING at the
        // venue: pendings (incl. STACK_AT layers) left alive keep filling into exactly
        // the situation the halt declared bad (FIA §1.11, RTS 6). Runs on the engine
        // thread via the bus reroute, so OrderManager state stays single-threaded.
        bus.subscribe<RiskEvent.Halted> { ev ->
            log.warn("halt ({}): cancelling venue-resting pendings for {} symbol(s)", ev.reason, symbols.size)
            for (symbol in symbols) {
                runCatching { pipeline.orderManager.cancelPendingForSymbol(symbol) }
                    .onFailure { e -> log.error("halt pending-cancel failed for {}: {}", symbol, e.message) }
            }
        }

        if (perStreamSpecs.isNotEmpty()) {
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

        val terminated = CountDownLatch(1)
        // Control events (bus events from pollers, flatten, heartbeat, feed-end) are
        // low-rate and must NEVER be dropped; ticks are high-rate and individually
        // disposable — a newer tick supersedes an older one. Splitting them bounds
        // memory under a stalled consumer: the tick queue sheds its OLDEST on overflow
        // and the daemon can no longer OOM because one engine thread stalled. The loop
        // drains control ahead of ticks, so a flatten or fill never waits behind a
        // tick backlog. [control] itself is created before broker construction — see
        // the bindSink note above.
        val tickQueue = java.util.concurrent.ArrayBlockingQueue<Inbound.FeedTick>(TICK_QUEUE_CAPACITY)
        val droppedInboundTicks =
            java.util.concurrent.atomic
                .AtomicLong(0)

        fun postTick(msg: Inbound.FeedTick) {
            while (!tickQueue.offer(msg)) {
                if (tickQueue.poll() != null) {
                    val dropped = droppedInboundTicks.incrementAndGet()
                    if (dropped == 1L) {
                        log.warn(
                            "inbound tick queue saturated (capacity {}) — shedding oldest ticks; " +
                                "the engine thread is not keeping up",
                            TICK_QUEUE_CAPACITY,
                        )
                    }
                }
            }
        }

        // Flattening mutates the OrderManager and publishes closes, so it must run on the engine
        // thread — the HTTP control path enqueues [Inbound.Flatten] rather than touching engine
        // state from its own worker thread.
        fun doFlatten() {
            val strategyId = strategies.firstOrNull()?.first ?: return
            for ((symbol, pos) in positions.allPositions()) {
                if (pos.quantity.signum() == 0) continue
                pipeline.orderManager.cancelPendingForSymbol(symbol)
                val side =
                    if (pos.quantity.signum() > 0) com.qkt.common.Side.SELL else com.qkt.common.Side.BUY
                bus.publish(
                    com.qkt.events.OrderEvent(
                        com.qkt.execution.OrderRequest.Market(
                            id = ids.next(),
                            symbol = symbol,
                            side = side,
                            quantity = pos.quantity.abs(),
                            timeInForce = com.qkt.execution.TimeInForce.GTC,
                            timestamp = clock.now(),
                            strategyId = strategyId,
                        ),
                    ),
                )
            }
        }

        // A strategy/indicator/handler exception must never kill this thread silently:
        // log with full context, raise a CRITICAL alert, halt the session's trading
        // (PERSISTENT — an operator resumes after diagnosing), and keep draining the
        // queue so exits, halts, and flattens still work.
        fun onEngineFault(
            stage: String,
            t: Throwable,
        ) {
            log.error("engine loop fault during {} — halting trading, loop stays alive", stage, t)
            runCatching { riskState.halt("engine fault: $stage: ${t.message}") }
            runCatching {
                notifier.notify(
                    NotificationEvent.StrategyError(
                        strategyId = strategies.firstOrNull()?.first.orEmpty(),
                        message = "engine loop fault during $stage: $t",
                        timestamp = clock.now(),
                    ),
                )
            }.onFailure { n -> log.warn("[notify] StrategyError alert failed", n) }
        }

        // The single-consumer engine loop: the ONE thread that touches the bus, OrderManager,
        // positions, and the schedule runner. The tick feed, the heartbeat, the broker pollers
        // (via the bus), and the HTTP flatten all POST onto [inbound]; this loop drains it
        // serially, restoring the "engine is single-threaded" invariant in live mode.
        val thread =
            Thread({
                if (mdcStrategy != null) org.slf4j.MDC.put("strategy", mdcStrategy)

                fun processTick(msg: Inbound.FeedTick) {
                    try {
                        // Drive event-time from the tick being PROCESSED (not when it was
                        // read off the feed) so a deterministic clock stays in lockstep with
                        // processing — preserving backtest==live. No-op for SystemClock.
                        (clock as? com.qkt.common.MutableClock)?.advanceTo(msg.tick.timestamp)
                        pipeline.ingest(msg.tick)
                    } catch (e: Exception) {
                        onEngineFault("tick ${msg.tick.symbol}@${msg.tick.timestamp}", e)
                    }
                }
                var lastInsightsSnapshotMs = 0L
                try {
                    while (running.get()) {
                        val msg: Inbound =
                            control.poll()
                                ?: tickQueue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                                ?: continue
                        when (msg) {
                            is Inbound.FeedTick -> processTick(msg)
                            is Inbound.BusEvent ->
                                try {
                                    bus.publish(msg.event)
                                } catch (e: Exception) {
                                    onEngineFault("event ${msg.event::class.simpleName}", e)
                                }
                            is Inbound.Heartbeat -> {
                                runCatching { pipeline.scheduleHeartbeat(msg.nowMs) }
                                    .onFailure { t -> onEngineFault("schedule heartbeat", t) }
                                if (insightsSink != null &&
                                    com.qkt.observe.insights.InsightsEventFamily.SNAPSHOT in insightsEvents &&
                                    msg.nowMs - lastInsightsSnapshotMs >= insightsSnapshotIntervalMs
                                ) {
                                    lastInsightsSnapshotMs = msg.nowMs
                                    runCatching {
                                        emitInsightsSnapshots(msg.nowMs, strategyPnL, strategyPositions)
                                    }.onFailure { t -> log.warn("[insights] snapshot failed: ${t.message}") }
                                }
                            }
                            Inbound.Flatten ->
                                // A failed FLATTEN is the emergency path failing — the loudest case.
                                runCatching { doFlatten() }
                                    .onFailure { t -> onEngineFault("flatten", t) }
                            Inbound.FeedEnded -> {
                                // Feed ended (finite source drained): process every tick already
                                // queued before stopping, so no tick is dropped.
                                while (true) processTick(tickQueue.poll() ?: break)
                                running.set(false)
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    log.info("LiveSession engine thread interrupted")
                    Thread.currentThread().interrupt()
                } finally {
                    running.set(false)
                    terminated.countDown()
                    if (mdcStrategy != null) org.slf4j.MDC.remove("strategy")
                }
            }, "qkt-live-engine")
        thread.isDaemon = true
        // Route every publish from a non-engine thread (broker pollers, WS readers) onto this
        // loop's queue, so subscribers only ever run on the engine thread.
        bus.bindEngineLoop(thread) { ev -> if (running.get()) control.put(Inbound.BusEvent(ev)) }
        thread.start()

        // Feed reader: turn the blocking tick feed into queue messages so the engine loop stays a
        // pure single consumer rather than blocking on the feed itself.
        val feedThread =
            Thread({
                try {
                    while (running.get()) {
                        val tick = feed.next() ?: break
                        postTick(Inbound.FeedTick(tick))
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    runCatching { feed.close() }
                    // Non-blocking: tell the consumer the feed is done so it drains-then-stops.
                    control.offer(Inbound.FeedEnded)
                }
            }, "qkt-live-feed")
        feedThread.isDaemon = true
        feedThread.start()

        // Quiet-market heartbeat (#77 Phase 40 follow-up). Without this, SCHEDULE fires only happen
        // when ticks arrive — a 19:55 UTC placement would slip by seconds on a quiet Asia session.
        // It posts onto the queue so scheduleRunner.tick only ever runs on the engine thread.
        val scheduleHeartbeat: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor { r ->
                    Thread(r, "qkt-schedule-heartbeat").apply { isDaemon = true }
                }
        scheduleHeartbeat.scheduleAtFixedRate(
            { runCatching { control.put(Inbound.Heartbeat(clock.now())) } },
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
                get() =
                    (if (feed is LiveTickFeed) feed.droppedTicks.get() else 0L) +
                        droppedInboundTicks.get()

            override fun inboundQueueDepth(): Int = control.size + tickQueue.size

            override fun staleSymbols(): Map<String, Long> = marketDataGate.staleSymbols()

            override fun reconcile(): ReconcileReport {
                val ownerId = strategies.firstOrNull()?.first.orEmpty()
                val brokerBySymbol =
                    runCatching { broker.getOpenPositions() }.getOrElse { emptyMap() }
                val symbolsToCheck =
                    (brokerBySymbol.keys + positions.allPositions().keys).toSortedSet()
                val deltas =
                    symbolsToCheck.mapNotNull { symbol ->
                        val engineQty =
                            positions.allPositions()[symbol]?.quantity ?: java.math.BigDecimal.ZERO
                        val brokerQty =
                            brokerBySymbol[symbol]
                                ?.fold(java.math.BigDecimal.ZERO) { acc, p -> acc.add(p.quantity) }
                                ?: java.math.BigDecimal.ZERO
                        if (engineQty.compareTo(brokerQty) == 0) {
                            null
                        } else {
                            PositionDelta(symbol, engineQty, brokerQty)
                        }
                    }
                return ReconcileReport(
                    deltas = deltas,
                    engineEquity = strategyPnL.equityFor(ownerId),
                    brokerEquity = runCatching { broker.accountEquity() }.getOrNull(),
                )
            }

            override fun stop() {
                running.set(false)
                thread.interrupt()
                feedThread.interrupt()
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

            override fun pnlSnapshot(strategyId: String): SessionPnl =
                SessionPnl(
                    equity = strategyPnL.equityFor(strategyId),
                    balance = strategyPnL.balanceFor(strategyId),
                    realized = strategyPnL.realizedFor(strategyId),
                    unrealized = strategyPnL.unrealizedTotalFor(strategyId),
                )

            override fun halt(reason: String) {
                riskState.halt(reason)
            }

            override fun resume() {
                riskState.resume()
            }

            override fun isHalted(): Boolean = riskState.halted

            // Run the actual flatten on the engine thread (it mutates the OrderManager and
            // publishes closes); this HTTP-pool call just posts the command onto the queue.
            override fun flatten() {
                control.put(Inbound.Flatten)
            }
        }
    }
}

/** Messages drained by the live engine loop's single consumer thread (see [LiveSession.start]). */
private sealed interface Inbound {
    data class FeedTick(
        val tick: com.qkt.marketdata.Tick,
    ) : Inbound

    data class BusEvent(
        val event: com.qkt.events.Event,
    ) : Inbound

    data class Heartbeat(
        val nowMs: Long,
    ) : Inbound

    object Flatten : Inbound

    object FeedEnded : Inbound
}
