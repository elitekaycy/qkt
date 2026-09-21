package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.events.SignalEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.execution.allIds
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.notify.DailyRollingTracker
import com.qkt.notify.NoopNotifier
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionProvider
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
import com.qkt.strategy.targetSymbol
import com.qkt.strategy.windowMs
import java.time.Instant
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
    /**
     * Strategy id → the `STRATEGY` name the DSL stamps into order comments, for hosts
     * whose runtime id differs from it (portfolio children: `forward_bench:s0` runs
     * `gold_silver_ratio_accel`). Empty when the two coincide.
     */
    private val strategyCommentNames: Map<String, String> = emptyMap(),
    private val rules: List<RiskRule> = emptyList(),
    private val haltRules: List<HaltRule> = emptyList(),
    private val source: MarketSource,
    private val symbols: List<String>,
    /** Market-data subscriptions, including non-traded FX conversion symbols. */
    private val feedSymbols: List<String> = symbols,
    private val candleWindow: TimeWindow? = null,
    private val clock: Clock = SystemClock(),
    private val calendar: TradingCalendar = TradingCalendar.fxDefault(),
    private val accountingConfig: com.qkt.accounting.AccountingConfig = com.qkt.accounting.AccountingConfig(),
    /** Equity source for standalone live sizing; portfolio children always use allocated model equity. */
    private val equityBasis: LiveEquityBasis = LiveEquityBasis.VENUE,
    private val warmupOverride: WarmupSpec? = null,
    private val mdcStrategy: String? = null,
    private val candleHub: com.qkt.dsl.compile.CandleHub? = null,
    private val onWarmupTick: (Tick) -> Unit = {},
    private val onTrade: (Trade, java.math.BigDecimal, String) -> Unit = { _, _, _ -> },
    private val onSignal: (Signal) -> Unit = {},
    private val gate: () -> Boolean = { true },
    private val bookRiskController: com.qkt.risk.book.BookRiskController? = null,
    private val brokerFactories: Map<String, BrokerFactory> = emptyMap(),
    /** Explicit instrument metadata source for embedded and deterministic test sessions. */
    private val instrumentRegistry: com.qkt.instrument.InstrumentRegistry? = null,
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
    private val perStrategyMaxTradesPerDay: Int? = null,
    private val perStrategyCooldownAfterLossMs: Long? = null,
    private val perStrategyCooldownAfterLossAfterConsecutive: Int = 1,
    private val perStrategyLossStreakHalt: Int? = null,
    private val perStrategyLossStreakHaltScope: com.qkt.risk.HaltScope = com.qkt.risk.HaltScope.PERSISTENT,
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
    /** Append-only all-event engine audit journal; null disables (tests, backtest replays). */
    private val auditJournal: com.qkt.observe.EngineAuditJournal? = null,
    /**
     * Best-effort egress to a qkt-insights collector; null disables (the default).
     * The daemon constructs one shared [com.qkt.observe.insights.InsightsSink] from
     * [com.qkt.cli.Config.insights] and passes the same instance to every session.
     * The engine thread only enqueues; the sink's own thread does JSON + HTTP.
     */
    private val insightsSink: com.qkt.observe.insights.InsightsSink? = null,
    /** Event families to stream; empty wires nothing even when a sink is present. */
    private val insightsEvents: Set<com.qkt.observe.insights.InsightsEventFamily> = emptySet(),
    /** Per-strategy runtime/source metadata to attach to `strategy.started` insights envelopes. */
    private val insightsStrategyMetadata: Map<String, Map<String, Any?>> = emptyMap(),
    /** All strategy ids deployed in this daemon, used to reject ambiguous truncated broker comments. */
    private val insightsDeployedIds: () -> Collection<String> = { emptyList() },
    /** Broker state poller cadence (insights `state_poll_ms`); active when the STATE family is enabled. */
    private val insightsStatePollMs: Long = 10_000L,
    private val insightsSharedDeals: com.qkt.observe.insights.SharedDealFetch =
        com.qkt.observe.insights
            .SharedDealFetch(),
    /** Days of broker deal history the state poller backfills at start (insights `deal_backfill_days`). */
    private val insightsDealBackfillDays: Long = 30L,
    /**
     * Market-data quality gate thresholds (the `market_data:` config block). The default
     * preserves the gate's historical hard-coded thresholds; the daemon passes
     * [com.qkt.cli.Config.marketData].
     */
    private val marketDataGateConfig: com.qkt.marketdata.MarketDataGateConfig =
        com.qkt.marketdata.MarketDataGateConfig.DEFAULT,
    /** Opt-in bounded hot-path timing; disabled leaves the tick loop without nano-time reads. */
    private val latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
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
     * How far behind the wall clock the heartbeat closes a quiet bar (#1058). A tick
     * stamped just before a boundary can still be in flight from the poller (one poll
     * round plus a gateway round trip) when the heartbeat fires; closing at
     * `now - grace` keeps it in its own bar instead of rejecting it as late. Tick-driven
     * closes are exact and unaffected; only a bar with no post-boundary tick closes up
     * to this much later in wall time.
     */
    private val candleCloseGraceMs: Long = DEFAULT_CANDLE_CLOSE_GRACE_MS,
    /**
     * Starting balance per strategy id, the basis for `ACCOUNT.equity`
     * (equity = starting balance + realized + unrealized). The portfolio deployer
     * supplies a child's allocated capital here (CAPITAL x WEIGHT) so the child sizes
     * off its slice of the book; standalone sessions leave it empty and equity starts
     * at zero. e.g. {"book:hs" -> 60000} -> the hs child's ACCOUNT.equity reads 60000.
     */
    private val startingBalances: Map<String, java.math.BigDecimal> = emptyMap(),
    /**
     * Balance of the portfolio book this session's strategies trade inside (CAPITAL +
     * realized PnL of every child), bound by [com.qkt.cli.daemon.portfolio.PortfolioDeployer];
     * null for standalone deploys. Read by `SIZING … RISK OF BOOK`.
     */
    private val bookBalance: com.qkt.pnl.BookBalanceView? = null,
    /**
     * Injectable event bus for tests that need to observe bus events (e.g. [com.qkt.events.RiskEvent]).
     * When `null` (the default), [start] constructs its own bus — the normal production path.
     * e.g. test passes a bus, subscribes to [com.qkt.events.RiskEvent.Halted], calls
     * [LiveSessionHandle.halt], then asserts the event arrived.
     */
    private val busOverride: EventBus? = null,
    /** Base backoff between reconcile read attempts; tests shrink it to keep retries fast. */
    private val reconcileReadBackoffMs: Long = 500L,
    /** Account-equity poll cadence; injectable so retry behavior is testable without long sleeps. */
    private val brokerEquityPollMs: Long = BROKER_EQUITY_POLL_MS,
    /** Maximum age of the last successful venue-equity sample before a critical alert. */
    private val brokerEquityStaleMs: Long = BROKER_EQUITY_STALE_MS,
) {
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    companion object {
        /** Attempts to read broker positions at reconcile before refusing to start. */
        const val RECONCILE_READ_ATTEMPTS: Int = 5

        /**
         * The server clock zone of the first of [brokers] that reports one
         * ([com.qkt.broker.ServerTimeZoneProvider]), or null when none does.
         */
        internal fun serverTimeZoneOf(brokers: List<Broker>): java.time.ZoneId? =
            brokers.filterIsInstance<com.qkt.broker.ServerTimeZoneProvider>().firstOrNull()?.serverTimeZone()

        /** How often to poll the broker for live account equity, off the engine thread (#352). */
        const val BROKER_EQUITY_POLL_MS: Long = 5_000L

        /** Three missed default polls make the broker equity unsafe for drawdown decisions. */
        const val BROKER_EQUITY_STALE_MS: Long = BROKER_EQUITY_POLL_MS * 3

        /**
         * Bound on buffered ticks awaiting the engine thread. At a heavy 100 ticks/sec
         * this is ~100s of backlog — far past the point where shedding the oldest tick
         * is strictly better than growing the heap.
         */
        const val TICK_QUEUE_CAPACITY: Int = 10_000

        /**
         * Wall-clock lag for heartbeat-driven bar closes (#1058): above the feed's tick arrival
         * lag, far below any bar window.
         *
         * Sized from measurement, not from the poll interval. Against a local Exness gateway the
         * lag between a tick's broker timestamp and its arrival at the aggregator ran a median of
         * 100ms and a p99 of 192ms unloaded, but the tail is set by gateway contention rather than
         * by cadence: a single MT5 terminal serializes requests, and a competing poller pushed the
         * same measurement to a 1458ms p90. The former 500ms sat inside that tail, so a heartbeat
         * could close a bar while its own ticks were still in flight and they were then rejected as
         * late — reproduced live as one bar that recorded 18 of the venue's 111 ticks while every
         * neighbouring bar matched exactly.
         *
         * A heartbeat close is only reached when no tick from the next bar has arrived, so an
         * active symbol closes tick-driven and is unaffected by this value; it costs close latency
         * on quiet symbols alone.
         */
        const val DEFAULT_CANDLE_CLOSE_GRACE_MS: Long = 2_000L

        /** Tick-queue poll timeout — bounds the control-queue re-check latency. */
        const val QUEUE_POLL_MS: Long = 25L
        const val STOP_DRAIN_GRACE_MS: Long = 2_000L
        const val FLATTEN_VERIFY_POLL_MS: Long = 100L

        /** HTTP/operator snapshot requests fail loud instead of waiting forever on a stalled engine. */
        const val ENGINE_QUERY_TIMEOUT_MS: Long = 5_000L
    }

    /** Accumulates trades/halts/equity-delta for the daily summary. */
    private val dailyTracker = DailyRollingTracker()

    /** Builds and remembers this session's venue brokers so the session can ask them for their abilities. */
    private val brokers = SessionBrokers(strategies, symbols, brokerFactories, instrumentRegistry)

    /**
     * Broker-ticket → strategy-id mirror for the insights state poller. Written on the
     * engine thread (fills) and at startup (recovery-seeded orphans); the poller only
     * reads, so it never touches engine-thread-only trackers.
     */
    internal val ticketAttribution =
        com.qkt.observe.insights
            .TicketAttribution()
            .also { attribution ->
                strategyCommentNames.forEach { (strategyId, name) -> attribution.alias(name, strategyId) }
            }

    // Kept as a member: LiveSessionBrokerCoverageTest reaches it reflectively by this name.
    private fun buildBroker(
        paperBroker: PaperBroker,
        bus: EventBus,
        clock: Clock,
        priceTracker: MarketPriceTracker,
        positions: PositionProvider,
    ): Broker = brokers.buildBroker(paperBroker, bus, clock, priceTracker, positions)

    private val perStrategyLimits =
        PerStrategyRiskLimits(
            perStrategyMaxDailyLoss,
            perStrategyMaxPositionSize,
            perStrategyMaxOpenPositions,
            perStrategyMaxDrawdownPct,
            perStrategyMaxDailyDrawdownPct,
            perStrategyMaxTradesPerDay,
            perStrategyCooldownAfterLossMs,
            perStrategyCooldownAfterLossAfterConsecutive,
            perStrategyLossStreakHalt,
            perStrategyLossStreakHaltScope,
        )

    private val sessionNotifier = SessionNotifier(notifier, notifyEvents, journal, strategies, clock)

    private fun recordNotificationFailure(
        strategyId: String?,
        handler: String,
        t: Throwable,
    ) = sessionNotifier.recordFailure(strategyId, handler, t)

    private val insights =
        InsightsLifecycle(
            insightsSink,
            insightsEvents,
            insightsStrategyMetadata,
            strategies,
            source,
            feedSymbols,
            clock,
        )

    private val summaryRows = DailySummaryRows(strategies, dailyTracker)

    fun start(): LiveSessionHandle {
        val ids = SequentialIdGenerator.forSession(strategies.map { it.first })
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val accounting = com.qkt.accounting.AccountingEngine(accountingConfig, priceTracker)
        com.qkt.instrument.QuoteCurrencyGuard
            .assertAccountQuoted(
                symbols,
                accountCurrency = accounting.accountCurrency,
                canConvert = { symbol, _ -> accounting.canConvertSymbol(symbol) },
            )
        val strategyPositions = StrategyPositionTracker(persistor)
        val positions = strategyPositions.account
        val bus = busOverride ?: EventBus(clock, sequencer)
        // The control queue and bus sink exist BEFORE any broker constructs: MT5
        // pollers start at construction and publish from their own threads — without
        // the sink those events dispatch inline against a half-built pipeline (#388).
        // They queue here and drain, in order, once the engine loop starts.
        val mailbox = EngineMailbox()
        val running = mailbox.running
        val stopping = mailbox.stopping
        val stopFinishing = mailbox.stopFinishing
        val clearRuleEdgesAtStop = mailbox.clearRuleEdgesAtStop
        val control = mailbox.control
        val tickQueue = mailbox.tickQueue
        val droppedInboundTicks = mailbox.droppedInboundTicks
        val terminated = mailbox.terminated
        bus.bindSink(mailbox::postBusEvent)
        val paperInstruments =
            java.util.concurrent.atomic.AtomicReference<com.qkt.instrument.InstrumentRegistry>(
                instrumentRegistry ?: com.qkt.instrument.NoopInstrumentRegistry,
            )
        val paperBroker =
            PaperBroker(
                bus,
                clock,
                priceTracker,
                object : com.qkt.instrument.InstrumentRegistry {
                    override fun lookup(qktSymbol: String) = paperInstruments.get().lookup(qktSymbol)
                },
                calendar = calendar,
            )
        val broker: Broker = buildBroker(paperBroker, bus, clock, priceTracker, positions)
        val usesAllocatedStrategyCapital = startingBalances.isNotEmpty()
        // Recovery seeding ran inside each broker's constructor; mirror the orphan ticket
        // attributions it produced so the state poller can name their strategy.
        for (b in brokers.built.filterIsInstance<com.qkt.broker.TicketAttributionProvider>()) {
            for ((ticket, strategyId) in b.ticketAttributions()) {
                ticketAttribution.record(ticket, strategyId)
            }
        }
        // Phase 30: registry must be built after the brokers so each broker that provides
        // venue contract specs can contribute them.
        val instruments = brokers.buildInstrumentRegistry()
        paperInstruments.set(instruments)
        val pnl = PnLCalculator(positions, priceTracker, instruments, accounting, markTimestamp = clock::now)
        // #352: live account equity, polled off the engine thread (a network call) into this holder
        // and read cheaply by StrategyPnL.equityFor. Allocated portfolio children keep this
        // disconnected because account equity cannot represent one child's share of the book.
        val brokerEquity =
            java.util.concurrent.atomic
                .AtomicReference<java.math.BigDecimal?>(null)
        val strategyPnL =
            StrategyPnL(
                strategyPositions,
                priceTracker,
                instruments,
                persistor,
                accounting = accounting,
                markTimestamp = clock::now,
                brokerEquity = {
                    if (usesAllocatedStrategyCapital || equityBasis == LiveEquityBasis.MODELED) {
                        null
                    } else {
                        brokerEquity.get()
                    }
                },
            )
        // Every deploy path needs a starting balance: portfolio deploys pass per-strategy
        // entries in [startingBalances]; standalone deploys fall back to the session-level
        // [initialBalance] so ACCOUNT.equity and % OF EQUITY sizing don't run on zero.
        // Lifetime realized PnL restores alongside, so equity continues from where the
        // last session ended instead of cliffing back to the starting balance.
        for ((id, _) in strategies) {
            val balance = startingBalances[id] ?: initialBalance
            if (balance.signum() > 0) strategyPnL.setStartingBalance(id, balance)
            strategyPnL.restore(id)
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
        val downtimeCloses = DowntimeCloses(broker, clock)
        val adoptedLegCounts =
            StartupReconcile(
                strategies,
                symbols,
                persistor,
                clock,
                ignoreMismatches,
                reconcileReadBackoffMs,
                ticketAttribution,
            ).run(strategyPositions, broker, downtimeCloses::onLegRetired)

        val engine = Engine(bus, priceTracker)
        val riskPersistId = strategies.firstOrNull()?.first ?: "session"
        val persistedRiskState = persistor.loadRiskState(riskPersistId)
        val restoredGlobalRealized =
            persistedRiskState?.globalRealizedTotal
                ?: strategies.fold(java.math.BigDecimal.ZERO) { total, (id, _) ->
                    total + strategyPnL.realizedFor(id)
                }
        pnl.restoreRealizedTotal(restoredGlobalRealized)
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
        // Restore the complete risk reference state before any live event can evaluate rules.
        persistedRiskState?.let { persisted ->
            riskState.restore(persisted)
            if (riskState.halted) {
                log.warn("restored HALTED risk state for {}: {}", riskPersistId, riskState.haltReason)
            }
        }
        riskState.initializeAnchors(strategies.map { it.first })
        // Positions adopted under ignore-mismatches carry no coherent per-leg history, and a
        // position-aware strategy running on such a book has produced a live fill/re-enter
        // loop (#1061). Fail closed: start the adopted strategy under a persistent
        // operator halt — exits and management stay live, new entries wait for a human
        // to review the adopted book and `qkt resume`.
        for ((adoptedId, legCount) in adoptedLegCounts) {
            val reason =
                "adopted $legCount venue position(s) via ignore-mismatches — " +
                    "review the adopted book, then resume"
            riskState.haltStrategy(adoptedId, reason)
            log.error("strategy {} starts HALTED: {}", adoptedId, reason)
        }
        val pacerLedger = riskState.pacerLedger

        // Phase 25D: per-strategy risk overrides for the (single) strategy in this session.
        // The daemon creates one LiveSession per deployed strategy, so the first entry is
        // the only one. If the caller didn't set per-strategy caps, these stay empty.
        val riskOwnerStrategyId = strategies.firstOrNull()?.first
        val perStrategyRules =
            PerStrategyRiskRules(
                perStrategyLimits,
                riskOwnerStrategyId,
                strategyPositions,
                startingBalances,
                initialBalance,
                totalDdBasis,
                pacerLedger,
                clock,
            )
        // Mandatory pre-trade controls are always on — they ship with defaults so "no
        // limit configured" can never mean "no limit" (#393).
        val preTradeRules =
            com.qkt.risk.rules.PreTradeControls.standard(
                prices = priceTracker,
                instruments = instruments,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                priceCollarFrac = priceCollarFrac,
                accounting = accounting,
            )
        val marketDataAlerts = MarketDataHealthAlerts(strategies, sessionNotifier, insights)
        // Stale/outlier judgment over the live feeds (#395): suppresses NEW orders on
        // frozen data and drops implausible ticks before they poison indicators.
        val marketDataGate =
            com.qkt.marketdata.MarketDataGate(
                clock = clock,
                staleAgeMultiple = marketDataGateConfig.staleAgeMultiple,
                minStaleAgeMs = marketDataGateConfig.minStaleAgeMs,
                outlierSigma = marketDataGateConfig.outlierSigma,
                maxClockSkewMs = marketDataGateConfig.maxClockSkewMs,
                inSession = { _, nowMs -> broker.marketOpen(nowMs) },
                scheduledBreak = { symbol, nowMs ->
                    brokers.built.ifEmpty { listOf(broker) }.any { it.scheduledBreak(symbol, nowMs) }
                },
                onUnhealthy = marketDataAlerts::onUnhealthy,
            )
        val entryGuards = EntryGuardRules(clock, marginFloorPct, measuredUsageHours, measuredUsageMaxQty)
        val marginRules = entryGuards.marginRules(broker)
        val measuredRules = entryGuards.measuredRules()
        val riskEngine =
            RiskEngine(
                rules + perStrategyRules.riskRules + preTradeRules + marginRules + measuredRules +
                    listOfNotNull(
                        bookRiskController?.let {
                            com.qkt.risk.rules
                                .BookExposureLimit(it, priceTracker, instruments, accounting)
                        },
                    ) +
                    com.qkt.marketdata.MarketDataHealthRule(marketDataGate),
                haltRules + perStrategyRules.haltRules,
                positions,
                riskState,
            )

        val trades = RecentTrades()

        val pipelineCandleHub =
            candleHub ?: com.qkt.dsl.compile
                .CandleHub()

        val now = Instant.ofEpochMilli(clock.now())
        val warmupCoordinator =
            PerStreamWarmupCoordinator(strategies, source, pipelineCandleHub, now)

        // Phase 25B: per-stream pre-fetch + hub seeding for DSL strategies. Seeding
        // must happen BEFORE TradingPipeline binds strategies to the hub: bindToHub
        // credits the WarmupGate from hub.historySize, so seeding afterwards leaves
        // the gate cold and every deploy waits out a full live warmup window on
        // already-warm indicators. register() is idempotent — the pipeline's later
        // registration extends these slots rather than replacing them. Retention is
        // widened to the warmup bar count so the seeded history survives the ring.
        // Fail-fast: any broker error here aborts deploy with a typed exception.
        warmupCoordinator.prepareHub()

        // A position restored over the restart has its excursion marks seeded from disk; the
        // bars the hub just loaded cover the downtime, so extend the marks with them (#1158).
        // The smallest declared timeframe per symbol gives the closest range.
        for ((strategyId, strategy) in strategies) {
            val dsl = strategy as? DslCompiledStrategy ?: continue
            val keysBySymbol = dsl.declaredStreams.values.groupBy { it.qktSymbol }
            for ((symbol, keys) in keysBySymbol) {
                if (strategyPositions.legBookFor(strategyId, symbol) == null) continue
                val key = keys.minByOrNull { TimeWindow.parse(it.timeframe).durationMs } ?: continue
                val bars =
                    (0 until pipelineCandleHub.historySize(key)).mapNotNull { n ->
                        pipelineCandleHub.history(key, n)
                    }
                strategyPositions.extendExcursion(strategyId, symbol, bars)
            }
        }

        // Resolver for `SCHEDULE … BROKER`: the server clock of the first broker in this
        // session's route list that has one. LiveSession is per-strategy in the daemon model,
        // so all calls return the same zone — strategy id is ignored. Null when no broker
        // reports a server clock (paper-only / Bybit-only sessions).
        val brokerZoneIdFor: ((String) -> java.time.ZoneId?)? =
            serverTimeZoneOf(brokers.built)?.let { zone -> { _: String -> zone } }

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
                positionMode = { symbol -> broker.positionAccountingMode(symbol) },
                pacerLedger = pacerLedger,
                pacerCooldownDurationMs = perStrategyCooldownAfterLossMs,
                pacerCooldownAfterConsecutive = perStrategyCooldownAfterLossAfterConsecutive,
                bookScaleFor = { id -> bookRiskController?.state()?.scaleFor(id) ?: java.math.BigDecimal.ONE },
                bookBalance = bookBalance,
                mode = Mode.LIVE,
                calendar = calendar,
                source = source,
                candleWindow = candleWindow,
                candleHub = pipelineCandleHub,
                accounting = accounting,
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
                onAccountedFill = insights::accountedFill,
                gate = gate,
                persistor = persistor,
                instruments = instruments,
                brokerZoneIdFor = brokerZoneIdFor,
                onProtectionFailure = { strategyId, message ->
                    sessionNotifier.strategyError(strategyId, "ProtectionFailure") { message }
                },
                latencyEnabled = latencyEnabled,
            )
        downtimeCloses.clearRuleEdges(strategies)
        marketDataAlerts.engineHeldProtectiveStopCount = pipeline.orderManager::engineHeldProtectiveStopCount

        bus.subscribe<WarmupTickEvent> { e -> onWarmupTick(e.tick) }
        bus.subscribe<SignalEvent> { e -> onSignal(e.signal) }
        // The pipeline logs a `submit …` context line before the risk decision; without a
        // paired rejection line the log reads as if the order reached the venue (#876).
        bus.subscribe<com.qkt.events.RiskRejectedEvent> { e ->
            log.warn(
                "risk rejected {} {} {} {} qty={}: {}",
                e.request.strategyId,
                e.request.id,
                e.request.symbol,
                e.request.side,
                e.request.quantity.toPlainString(),
                e.reason,
            )
        }
        // A gated drop never becomes an OrderRequest, so the rejection WARN above can't
        // cover it; without this line such signals vanish without trace (#889).
        bus.subscribe<com.qkt.events.SignalSuppressedEvent> { e ->
            log.warn(
                "signal suppressed {} {} {}: {}",
                e.strategyId,
                e.signal.targetSymbol() ?: "-",
                e.signal::class.simpleName,
                e.reason,
            )
        }
        journal?.let { OrderJournalWiring(strategies).wire(bus, it) }
        auditJournal?.let { audit -> bus.subscribeAllFirst { e -> audit.append(e) } }

        // Register notifier handlers before the warmup phase so a warmup-time risk halt
        // (rare but possible) reaches Telegram. Bus dispatch is single-threaded and synchronous,
        // so any publish that happens after this line will see the new subscribers.
        NotifierSubscriptions(notifier, notifyEvents, strategies, sessionNotifier::recordFailure)
            .wire(bus, pipeline.orderManager)
        // Every fill names its venue ticket. Reconciliation needs this attribution even when
        // insights are disabled, or every live ticket is misclassified as an orphan.
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            ticketAttribution.record(e.brokerOrderId, e.strategyId)
        }
        // Book reservations are released or aged by this session's own order lifecycle. Registered
        // after the pipeline, so a fill is already folded into positions when it is marked.
        bookRiskController?.let { controller ->
            com.qkt.risk.book
                .wireBookReservations(bus, controller)
        }
        insightsSink?.let { sink -> InsightsBusWiring(insightsEvents).wire(bus, sink, priceTracker) }
        // Restore OCO legs from the persistor and reconcile them against venue truth so
        // any sibling whose pair filled during downtime is cancelled before ticks flow.
        pipeline.orderManager.restore(strategies.map { it.first })
        // Restored orders and legs carry ids the strategy minted before the restart; its
        // sequence must continue past them or the next submit collides with a restored one.
        for ((strategyId, strategy) in strategies) {
            val dsl = strategy as? com.qkt.dsl.compile.DslCompiledStrategy ?: continue
            val usedIds =
                pipeline.orderManager
                    .activeOrders()
                    .filter { it.request.strategyId == strategyId }
                    .flatMap { it.request.allIds() + it.id } +
                    strategyPositions.allLegsFor(strategyId).map { it.legId }
            dsl.resumeOrderIds(usedIds)
            ids.resumePast(usedIds)
        }
        downtimeCloses.bookInto(pipeline)
        // The broker keeps the ledger honest against venue truth from here on (#1097).
        val watchedStrategyIds = strategies.map { it.first }
        broker.watchBookedLegs {
            val legs = ArrayList<com.qkt.broker.BookedLeg>()
            for (strategyId in watchedStrategyIds) {
                for (leg in strategyPositions.allLegsFor(strategyId)) {
                    val ticket = leg.brokerTicket ?: continue
                    legs +=
                        com.qkt.broker.BookedLeg(
                            strategyId = strategyId,
                            legId = leg.legId,
                            ticket = ticket,
                            symbol = leg.symbol,
                            side = leg.side,
                            quantity = leg.quantity,
                            entryPrice = leg.entryPrice,
                            openedAt = leg.openedAt,
                        )
                }
            }
            legs
        }
        // Keep the daily-summary tracker's halt count current. The daemon owns the one
        // DailySummaryScheduler; this session just feeds its tracker.
        val ownerStrategyId = strategies.firstOrNull()?.first.orEmpty()
        bus.subscribe<RiskEvent.Halted> { ev ->
            dailyTracker.recordHalt(ev.strategyId ?: ownerStrategyId)
        }
        // Runs on the engine thread via the bus reroute, so OrderManager state stays
        // single-threaded. The sweep removes entry intent only; protective exits survive.
        bus.subscribe<RiskEvent.Halted> { ev ->
            if (!ev.cancelWorkingOrders) {
                log.error(
                    "entry-only halt ({}): venue-resting protection remains active",
                    ev.reason,
                )
                return@subscribe
            }
            log.warn(
                "halt ({}): cancelling active entry orders for scope {}",
                ev.reason,
                ev.strategyId ?: "global",
            )
            runCatching { pipeline.orderManager.cancelEntriesForHalt(ev.strategyId) }
                .onFailure { e -> log.error("halt entry-cancel failed: {}", e.message) }
        }

        if (warmupCoordinator.specs.isNotEmpty()) {
            warmupCoordinator.warm(pipeline)
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

        val feed = source.liveTicks(feedSymbols)
        insights.connected({ brokers.built.ifEmpty { listOf(broker) } }, feed)

        val sessionFlatten =
            SessionFlatten(strategies, clock, broker, ticketAttribution, pipeline, strategyPositions, ids, bus)

        val faults = EngineFaults(strategies, riskState, sessionNotifier)
        val persistenceWatch = PersistenceHealthWatch(strategies, persistor, riskState, sessionNotifier, insights)

        val thread =
            EngineLoop(
                mailbox,
                pipeline,
                clock,
                bus,
                strategies,
                feedSymbols,
                marketDataGate,
                candleCloseGraceMs,
                mdcStrategy,
                journal,
                auditJournal,
                faults,
                persistenceWatch,
                sessionFlatten,
                sessionNotifier,
            ).newThread()
        // Route every publish from a non-engine thread (broker pollers, WS readers) onto this
        // loop's queue, so subscribers only ever run on the engine thread.
        bus.bindEngineLoop(thread, mailbox::postBusEvent)
        control.put(Inbound.PersistenceHealthCheck)
        thread.start()

        val feedThread = FeedReader(feed, mailbox, insights).newThread()
        feedThread.start()

        val pollers = SessionPollers(mailbox, riskState, clock, broker, bus)
        val scheduleHeartbeat = pollers.startScheduleHeartbeat(scheduleHeartbeatIntervalMs)
        val equityPoller =
            pollers.startEquityPoller(
                standaloneVenueEquity =
                    !usesAllocatedStrategyCapital &&
                        equityBasis == LiveEquityBasis.VENUE &&
                        strategies.size == 1,
                brokerEquity = brokerEquity,
                brokerEquityStaleMs = brokerEquityStaleMs,
                brokerEquityPollMs = brokerEquityPollMs,
            )

        val brokerStatePollerBrokers = brokers.built.ifEmpty { listOf(broker) }.distinct()
        val handleRef =
            java.util.concurrent.atomic
                .AtomicReference<LiveSessionHandle?>(null)
        val brokerStatePoller =
            InsightsStatePolling(
                strategies,
                insightsSink,
                insightsEvents,
                insights,
                ticketAttribution,
                insightsDeployedIds,
                insightsStatePollMs,
                insightsSharedDeals,
                insightsDealBackfillDays,
                clock,
            ).start(brokerStatePollerBrokers, handleRef, strategyPnL)

        // Fire StrategyStarted per strategy this session hosts. Lifecycle events bypass the
        // bus because no other engine component consumes them.
        sessionNotifier.strategiesStarted()
        insights.strategiesStarted()

        val snapshot = EngineSnapshot(thread, mailbox)
        return RunningSessionHandle(
            strategies = strategies,
            mailbox = mailbox,
            feed = feed,
            pipeline = pipeline,
            marketDataGate = marketDataGate,
            persistor = persistor,
            trades = trades,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            priceTracker = priceTracker,
            instruments = instruments,
            riskState = riskState,
            broker = broker,
            ticketAttribution = ticketAttribution,
            clock = clock,
            snapshot = snapshot,
            reconcileReport =
                SessionReconcileReport(
                    strategies,
                    symbols,
                    broker,
                    ticketAttribution,
                    strategyPositions,
                    strategyPnL,
                    snapshot,
                ),
            shutdown =
                SessionShutdown(
                    strategies,
                    mailbox,
                    thread,
                    feedThread,
                    feed,
                    brokerStatePoller,
                    scheduleHeartbeat,
                    equityPoller,
                    brokers.built,
                    riskState,
                    pipelineCandleHub,
                    sessionNotifier,
                    insights,
                ),
            summaryRows = summaryRows,
        ).also { handleRef.set(it) }
    }
}
