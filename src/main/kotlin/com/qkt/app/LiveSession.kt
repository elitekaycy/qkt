package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.CompositeBroker
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
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.notify.DailyRollingTracker
import com.qkt.notify.EventTranslator
import com.qkt.notify.NoopNotifier
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.notify.StrategySummary
import com.qkt.persistence.PersistenceHealth
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
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
import com.qkt.strategy.targetSymbol
import com.qkt.strategy.windowMs
import java.time.Duration
import java.time.Instant
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
    /** Broker state poller cadence (insights `state_poll_ms`); active when the STATE family is enabled. */
    private val insightsStatePollMs: Long = 10_000L,
    /** Days of broker deal history the state poller backfills at start (insights `deal_backfill_days`). */
    private val insightsDealBackfillDays: Long = 30L,
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

    private companion object {
        /** Attempts to read broker positions at reconcile before refusing to start. */
        const val RECONCILE_READ_ATTEMPTS: Int = 5

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

        /** Tick-queue poll timeout — bounds the control-queue re-check latency. */
        const val QUEUE_POLL_MS: Long = 25L
        const val STOP_DRAIN_GRACE_MS: Long = 2_000L
        const val FLATTEN_VERIFY_POLL_MS: Long = 100L

        /** HTTP/operator snapshot requests fail loud instead of waiting forever on a stalled engine. */
        const val ENGINE_QUERY_TIMEOUT_MS: Long = 5_000L
    }

    /** Accumulates trades/halts/equity-delta for the daily summary. */
    private val dailyTracker = DailyRollingTracker()

    private fun ticketPosition(ticket: BrokerPositionTicket): Position =
        Position(
            symbol = ticket.symbol,
            quantity =
                if (ticket.side == com.qkt.common.Side.BUY) {
                    ticket.qty
                } else {
                    ticket.qty.negate()
                },
            avgEntryPrice = ticket.entryPrice,
        )

    private fun ticketSnapshotMatches(
        brokerPositions: Map<String, List<Position>>,
        tickets: List<BrokerPositionTicket>,
    ): Boolean {
        val ticketPositions =
            tickets
                .groupBy(BrokerPositionTicket::symbol)
                .mapValues { (_, values) -> values.map(::ticketPosition) }
        if (brokerPositions.keys != ticketPositions.keys) return false
        return brokerPositions.all { (symbol, positions) ->
            val unmatched = ticketPositions.getValue(symbol).toMutableList()
            val allMatched =
                positions.all { position ->
                    val index =
                        unmatched.indexOfFirst { candidate ->
                            candidate.quantity.compareTo(position.quantity) == 0 &&
                                candidate.avgEntryPrice.compareTo(position.avgEntryPrice) == 0
                        }
                    if (index < 0) {
                        false
                    } else {
                        unmatched.removeAt(index)
                        true
                    }
                }
            allMatched && unmatched.isEmpty()
        }
    }

    private fun qktOrderMarker(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val marker =
            if (value.startsWith("oco:")) {
                value.substringAfter('/', missingDelimiterValue = "")
            } else {
                value
            }
        return marker.takeIf { it.startsWith("dsl-") }
    }

    private fun isPotentiallyOwnedBy(
        ticket: BrokerPositionTicket,
        strategyId: String,
    ): Boolean {
        ticketAttribution.ownerOf(ticket.ticket)?.let { return it == strategyId }
        val marker = qktOrderMarker(ticket.clientOrderId) ?: qktOrderMarker(ticket.comment) ?: return true
        return ticketAttribution.fromComment(marker, listOf(strategyId)) == strategyId
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
        // Venue tickets for adopting unmatched positions under ignore-mismatches. positionTickets()
        // is qkt-keyed and carries the broker ticket; getOpenPositions() above is ticketless, and a
        // leg adopted without its ticket can't be closed per-leg on a hedging account (#437).
        val brokerTicketRead = runCatching { broker.positionTickets() }
        if (broker.supportsPositionTickets && brokerTicketRead.isFailure) {
            log.warn(
                "reconcile: position-ticket read failed; retaining magic-global fail-closed behavior: {}",
                brokerTicketRead.exceptionOrNull()?.message,
            )
        }
        val brokerTickets = brokerTicketRead.getOrElse { emptyList() }
        val scopeByTicket =
            broker.supportsPositionTickets &&
                strategies.size == 1 &&
                brokerTicketRead.isSuccess &&
                ticketSnapshotMatches(
                    brokerPositions = brokerByQktSymbol,
                    tickets = brokerTickets,
                )
        if (broker.supportsPositionTickets && strategies.size == 1 && brokerTicketRead.isSuccess && !scopeByTicket) {
            log.warn("reconcile: position and ticket snapshots differ; retaining magic-global fail-closed behavior")
        }
        val brokerTicketsBySymbol = brokerTickets.groupBy(BrokerPositionTicket::symbol)
        val reconciler = com.qkt.persistence.LegBookReconciler(persistor)
        for ((strategyId, _) in strategies) {
            for (symbol in symbols) {
                val allTicketsForSymbol = brokerTicketsBySymbol[symbol].orEmpty()
                val ticketsForStrategy =
                    if (scopeByTicket) {
                        allTicketsForSymbol.filter { ticket -> isPotentiallyOwnedBy(ticket, strategyId) }
                    } else {
                        allTicketsForSymbol
                    }
                if (scopeByTicket && ticketsForStrategy.size != allTicketsForSymbol.size) {
                    log.info(
                        "reconcile: excluded {} position(s) on {} clearly attributed to another strategy",
                        allTicketsForSymbol.size - ticketsForStrategy.size,
                        symbol,
                    )
                }
                val brokerForSymbol =
                    if (scopeByTicket) {
                        ticketsForStrategy.map(::ticketPosition)
                    } else {
                        brokerByQktSymbol[symbol] ?: emptyList()
                    }
                val outcome = reconciler.reconcile(strategyId, symbol, brokerForSymbol)
                when (outcome) {
                    is com.qkt.persistence.LegBookReconciler.Outcome.Attached -> {
                        // Rebuild the whole book from disk — the engine hasn't run yet, so use the
                        // persistor preload path rather than applyFill. preloadFromPersistor loads
                        // every leg regardless of role, so call it once per reconciled (strategy,
                        // symbol). The old per-leg PRIMARY gate skipped any book with no PRIMARY leg
                        // — every OCO/straddle book is INDEPENDENT legs — so those positions were
                        // left out of the tracker after a restart: POSITION.<stream> read 0 and the
                        // dsl bracket + winner-timeout were dead (#432).
                        strategyPositions.preloadFromPersistor(strategyId, symbol)
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
                        // Adopt each unmatched broker position as an INDEPENDENT leg carrying its
                        // venue ticket, so CLOSE / winner-timeout flattens it per-leg by ticket. A
                        // STACK leg with a synthetic parent — or any ticketless leg — can only be
                        // closed by a net opposite order, which on a hedging account opens a counter
                        // position instead of closing it (#437). Prefer the ticketed view; fall back
                        // to the ticketless positions only on venues that expose no tickets, where a
                        // net close still flattens correctly.
                        val attachLegs =
                            if (ticketsForStrategy.isNotEmpty()) {
                                ticketsForStrategy.map { t ->
                                    val venueStop = t.stopLoss?.takeIf { it.signum() > 0 }
                                    val venueTarget = t.takeProfit?.takeIf { it.signum() > 0 }
                                    if (venueStop == null) {
                                        log.error(
                                            "ADOPTING UNPROTECTED position after explicit ignore-mismatches ack: " +
                                                "strategy={} symbol={} ticket={} venueStop=none venueTarget={}",
                                            strategyId,
                                            symbol,
                                            t.ticket,
                                            venueTarget?.toPlainString() ?: "none",
                                        )
                                    } else {
                                        log.warn(
                                            "adopting position after explicit ignore-mismatches ack: " +
                                                "strategy={} symbol={} ticket={} venueStop={} venueTarget={}",
                                            strategyId,
                                            symbol,
                                            t.ticket,
                                            venueStop.toPlainString(),
                                            venueTarget?.toPlainString() ?: "none",
                                        )
                                    }
                                    com.qkt.positions.PositionLeg(
                                        legId = "$strategyId-$symbol-reconciled-${t.ticket}",
                                        symbol = symbol,
                                        side = t.side,
                                        quantity = t.qty.abs(),
                                        entryPrice = t.entryPrice,
                                        openedAt = clock.now(),
                                        role = com.qkt.positions.LegRole.INDEPENDENT,
                                        brokerTicket = t.ticket,
                                    )
                                }
                            } else {
                                brokerForSymbol.map { pos ->
                                    val side =
                                        if (pos.quantity.signum() >= 0) {
                                            com.qkt.common.Side.BUY
                                        } else {
                                            com.qkt.common.Side.SELL
                                        }
                                    com.qkt.positions.PositionLeg(
                                        legId = "$strategyId-$symbol-reconciled-${pos.quantity}",
                                        symbol = symbol,
                                        side = side,
                                        quantity = pos.quantity.abs(),
                                        entryPrice = pos.avgEntryPrice,
                                        openedAt = clock.now(),
                                        role = com.qkt.positions.LegRole.INDEPENDENT,
                                    )
                                }
                            }
                        for (leg in attachLegs) {
                            strategyPositions.addIndependentLeg(strategyId, leg)
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

    /**
     * Broker-ticket → strategy-id mirror for the insights state poller. Written on the
     * engine thread (fills) and at startup (recovery-seeded orphans); the poller only
     * reads, so it never touches engine-thread-only trackers.
     */
    internal val ticketAttribution =
        com.qkt.observe.insights
            .TicketAttribution()

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
        // A configured live session must fail closed. Any symbol outside the declared route set
        // is a typo, stale profile, or incomplete deployment — paper-filling it creates a phantom
        // position that exists only inside qkt. Explicit paper sessions returned above still use
        // PaperBroker directly.
        return CompositeBroker(routes = routes, fallback = null, bus = bus)
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
        val layers = mt5Registries + listOfNotNull(instrumentRegistry)
        return when (layers.size) {
            0 -> com.qkt.instrument.NoopInstrumentRegistry
            1 -> layers.single()
            else -> com.qkt.instrument.LayeredInstrumentRegistry(layers)
        }
    }

    /**
     * Every order-lifecycle event lands in the append-only journal, in bus order.
     *
     * An [com.qkt.events.OrderEvent] only exists because risk approved the request, so the
     * submit path writes ONE `"submit"` line with `"approved":"true"` instead of separate
     * `risk-approved` + `submit` lines — one durable write per submit, not two (#648).
     */
    private fun wireJournal(
        bus: EventBus,
        journal: com.qkt.observe.OrderJournal,
    ) {
        fun orderFields(request: com.qkt.execution.OrderRequest): Map<String, String?> =
            mapOf(
                "id" to request.id,
                "type" to request::class.simpleName,
                "symbol" to request.symbol,
                "side" to request.side.name,
                "qty" to request.quantity.toPlainString(),
            )

        bus.subscribe<com.qkt.events.OrderEvent> { e ->
            journal.append(
                e.request.strategyId,
                "submit",
                orderFields(e.request) + ("approved" to "true"),
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
        bus.subscribe<BrokerEvent.PositionProtectionChanged> { e ->
            journal.append(
                e.strategyId.ifBlank { strategies.firstOrNull()?.first.orEmpty() },
                "position-protection-changed",
                mapOf(
                    "broker" to e.broker,
                    "symbol" to e.symbol,
                    "ticket" to e.ticket,
                    "oldSl" to e.oldStopLoss.toPlainString(),
                    "newSl" to e.newStopLoss.toPlainString(),
                    "oldTp" to e.oldTakeProfit.toPlainString(),
                    "newTp" to e.newTakeProfit.toPlainString(),
                ),
            )
        }
        bus.subscribe<com.qkt.events.RiskRejectedEvent> { e ->
            journal.append(
                e.request.strategyId,
                "risk-rejected",
                mapOf("id" to e.request.id, "symbol" to e.request.symbol, "reason" to e.reason),
            )
        }
        bus.subscribe<com.qkt.events.SignalSuppressedEvent> { e ->
            journal.append(
                e.strategyId,
                "signal-suppressed",
                mapOf("symbol" to e.signal.targetSymbol(), "reason" to e.reason),
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
                    .onFailure { t -> recordNotificationFailure(ev.strategyId, "Halted", t) }
            }
        }
        if (NotifyEventKind.RESUMED in notifyEvents) {
            bus.subscribe<RiskEvent.Resumed> { ev ->
                runCatching { notifier.notify(EventTranslator.fromRiskResumed(ev)) }
                    .onFailure { t -> recordNotificationFailure(ev.strategyId, "Resumed", t) }
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
                }.onFailure { t -> recordNotificationFailure(ownerStrategyId, "PositionReconciled", t) }
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
                }.onFailure { t -> recordNotificationFailure(ownerForError, "GatewayUnreachable", t) }
            }
            bus.subscribe<BrokerEvent.AccountEquityStale> { ev ->
                runCatching {
                    val age = ev.staleForMs?.let { "last good sample is ${it}ms old" } ?: "no successful sample"
                    notifier.notify(
                        NotificationEvent.StrategyError(
                            strategyId = ownerForError,
                            message =
                                "Broker equity '${ev.broker}' unavailable for ${ev.consecutiveFailures} " +
                                    "consecutive polls ($age) — drawdown basis is stale",
                            timestamp = ev.timestamp,
                        ),
                    )
                }.onFailure { t -> recordNotificationFailure(ownerForError, "AccountEquityStale", t) }
            }
            bus.subscribe<BrokerEvent.PositionProtectionChanged> { ev ->
                runCatching {
                    notifier.notify(
                        NotificationEvent.StrategyError(
                            strategyId = ev.strategyId.ifBlank { ownerForError },
                            message =
                                "CRITICAL venue protection changed: ${ev.broker} ${ev.symbol} ticket=${ev.ticket} " +
                                    "SL ${ev.oldStopLoss}->${ev.newStopLoss}, " +
                                    "TP ${ev.oldTakeProfit}->${ev.newTakeProfit}",
                            timestamp = ev.timestamp,
                        ),
                    )
                }.onFailure { t -> recordNotificationFailure(ownerForError, "PositionProtectionChanged", t) }
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
                }.onFailure { t -> recordNotificationFailure(ev.strategyId, "OrderRejected", t) }
            }
        }
    }

    private fun recordNotificationFailure(
        strategyId: String?,
        handler: String,
        t: Throwable,
    ) {
        log.warn("[notify] handler failed for {}", handler, t)
        journal?.append(
            strategyId.orEmpty(),
            "notification_failed",
            mapOf(
                "handler" to handler,
                "reason" to (t.message ?: t::class.java.simpleName),
            ),
        )
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
            bus.subscribe<com.qkt.events.SignalSuppressedEvent> { e -> sink.offer(t.fromSignalSuppressed(e)) }
            bus.subscribe<RiskEvent.Halted> { e -> sink.offer(t.fromRiskHalted(e)) }
            bus.subscribe<RiskEvent.Resumed> { e -> sink.offer(t.fromRiskResumed(e)) }
        }
        if (com.qkt.observe.insights.InsightsEventFamily.POSITION in insightsEvents) {
            bus.subscribe<BrokerEvent.PositionReconciled> { e -> sink.offer(t.fromPositionReconciled(e)) }
            bus.subscribe<BrokerEvent.BalancesUpdated> { e -> sink.offer(t.fromBalancesUpdated(e)) }
            bus.subscribe<BrokerEvent.GatewayUnreachable> { e -> sink.offer(t.fromGatewayUnreachable(e)) }
        }
        if (com.qkt.observe.insights.InsightsEventFamily.LIFECYCLE in insightsEvents) {
            bus.subscribe<BrokerEvent.GatewayUnreachable> { e -> sink.offer(t.fromBrokerGatewayUnreachable(e)) }
            bus.subscribe<BrokerEvent.ConnectionChanged> { e -> sink.offer(t.fromBrokerConnectionChanged(e)) }
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
        val accounting = com.qkt.accounting.AccountingEngine(accountingConfig, priceTracker)
        com.qkt.instrument.QuoteCurrencyGuard
            .assertAccountQuoted(
                symbols,
                accountCurrency = accounting.accountCurrency,
                canConvert = { symbol, _ -> accounting.canConvertSymbol(symbol) },
            )
        val positions = PositionTracker()
        val strategyPositions = StrategyPositionTracker(persistor)
        val bus = busOverride ?: EventBus(clock, sequencer)
        // The control queue and bus sink exist BEFORE any broker constructs: MT5
        // pollers start at construction and publish from their own threads — without
        // the sink those events dispatch inline against a half-built pipeline (#388).
        // They queue here and drain, in order, once the engine loop starts.
        val running = AtomicBoolean(true)
        val stopping = AtomicBoolean(false)
        val control = java.util.concurrent.LinkedBlockingQueue<Inbound>()
        bus.bindSink { ev -> if (running.get()) control.put(Inbound.BusEvent(ev)) }
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
            )
        val broker: Broker = buildBroker(paperBroker, bus, clock, priceTracker, positions)
        val usesAllocatedStrategyCapital = startingBalances.isNotEmpty()
        // Recovery seeding ran inside each MT5 broker's constructor; mirror the orphan
        // ticket attributions it produced so the state poller can name their strategy.
        for (b in builtBrokers.filterIsInstance<com.qkt.broker.mt5.MT5Broker>()) {
            for ((ticket, strategyId) in b.ticketAttributions()) {
                ticketAttribution.record(ticket, strategyId)
            }
        }
        // Phase 30: registry must be built after the brokers so [MT5InstrumentRegistry]
        // can wrap the [com.qkt.broker.mt5.MT5Broker] instance if one was constructed.
        val instruments = buildInstrumentRegistry()
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
        reconcileOrPreload(strategyPositions, broker)

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
        val pacerLedger = riskState.pacerLedger

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
            perStrategyMaxTradesPerDay?.let {
                perStrategyRiskRules.add(
                    com.qkt.risk.rules
                        .MaxTradesPerDay(it, pacerLedger, clock, riskOwnerStrategyId),
                )
            }
            perStrategyCooldownAfterLossMs?.let {
                perStrategyRiskRules.add(
                    com.qkt.risk.rules
                        .CooldownAfterLoss(
                            durationMs = it,
                            ledger = pacerLedger,
                            clock = clock,
                            afterConsecutive = perStrategyCooldownAfterLossAfterConsecutive,
                            strategyId = riskOwnerStrategyId,
                        ),
                )
            }
            perStrategyLossStreakHalt?.let {
                perStrategyHaltRules.add(
                    com.qkt.risk.rules
                        .LossStreakHalt(
                            strategyId = riskOwnerStrategyId,
                            maxLosses = it,
                            ledger = pacerLedger,
                            scope = perStrategyLossStreakHaltScope,
                        ),
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
                accounting = accounting,
            )
        var engineHeldProtectiveStopCount: () -> Int = { 0 }
        // Stale/outlier judgment over the live feeds (#395): suppresses NEW orders on
        // frozen data and drops implausible ticks before they poison indicators.
        val marketDataGate =
            com.qkt.marketdata.MarketDataGate(
                clock = clock,
                onUnhealthy = { symbol, reason ->
                    if (NotifyEventKind.STRATEGY_ERROR in notifyEvents) {
                        for ((strategyId, _) in strategies) {
                            runCatching {
                                notifier.notify(
                                    NotificationEvent.StrategyError(
                                        strategyId = strategyId,
                                        message =
                                            "market data unhealthy for $symbol: $reason; " +
                                                "${engineHeldProtectiveStopCount()} engine-held protective stop(s) " +
                                                "cannot trigger without ticks",
                                        timestamp = clock.now(),
                                    ),
                                )
                            }.onFailure { t -> recordNotificationFailure(strategyId, "MarketDataUnhealthy", t) }
                        }
                    }
                    if (insightsSink != null &&
                        com.qkt.observe.insights.InsightsEventFamily.LIFECYCLE in insightsEvents
                    ) {
                        insightsSink.offer(
                            com.qkt.observe.insights.InsightsTranslate.marketDataStale(
                                source = source.name,
                                symbol = symbol,
                                ts = clock.now(),
                                reason = reason,
                            ),
                        )
                    }
                },
            )
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
                    listOfNotNull(
                        bookRiskController?.let {
                            com.qkt.risk.rules
                                .BookExposureLimit(it, priceTracker, instruments, accounting)
                        },
                    ) +
                    com.qkt.marketdata.MarketDataHealthRule(marketDataGate),
                haltRules + perStrategyHaltRules,
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

        // Resolver for `SCHEDULE … BROKER`: take the first MT5 broker in this
        // session's route list and use its profile's DST-aware server clock.
        // LiveSession is per-strategy in the daemon model, so all calls return
        // the same zone — strategy id is ignored. Null when no MT5 broker is
        // in play (paper-only / Bybit-only sessions).
        val brokerZoneIdFor: ((String) -> java.time.ZoneId?)? =
            run {
                val mt5 = builtBrokers.filterIsInstance<com.qkt.broker.mt5.MT5Broker>().firstOrNull()
                if (mt5 != null) {
                    val zone: java.time.ZoneId = mt5.profile.serverTimeZone.asZoneId()
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
                onAccountedFill = { trade, convertedRealized, strategyId, fillState ->
                    // Per-close net P&L for insights analytics. Entry commissions are real
                    // cash movements, but they are not closed trades; only exposure-reducing
                    // fills ship through the legacy trade.closed stream.
                    val netRealized = fillState.netAccountRealized
                    if (insightsSink != null &&
                        fillState.reducedExposure &&
                        netRealized.signum() != 0 &&
                        com.qkt.observe.insights.InsightsEventFamily.TRADE in insightsEvents
                    ) {
                        insightsSink.offer(
                            com.qkt.observe.insights.InsightsTranslate
                                .tradeClosed(
                                    trade = trade,
                                    netAccountRealized = netRealized,
                                    strategyId = strategyId,
                                    convertedRealized = convertedRealized,
                                ),
                        )
                    }
                },
                gate = gate,
                persistor = persistor,
                instruments = instruments,
                brokerZoneIdFor = brokerZoneIdFor,
                onProtectionFailure = { strategyId, message ->
                    runCatching {
                        notifier.notify(
                            NotificationEvent.StrategyError(
                                strategyId = strategyId,
                                message = message,
                                timestamp = clock.now(),
                            ),
                        )
                    }.onFailure { t -> recordNotificationFailure(strategyId, "ProtectionFailure", t) }
                },
            )
        engineHeldProtectiveStopCount = pipeline.orderManager::engineHeldProtectiveStopCount

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
        journal?.let { wireJournal(bus, it) }
        auditJournal?.let { audit -> bus.subscribeAll { e -> audit.append(e) } }

        // Register notifier handlers before the warmup phase so a warmup-time risk halt
        // (rare but possible) reaches Telegram. Bus dispatch is single-threaded and synchronous,
        // so any publish that happens after this line will see the new subscribers.
        wireNotifierSubscriptions(bus, pipeline.orderManager)
        // Every fill names its venue ticket. Reconciliation needs this attribution even when
        // insights are disabled, or every live ticket is misclassified as an orphan.
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            ticketAttribution.record(e.brokerOrderId, e.strategyId)
        }
        insightsSink?.let { sink -> wireInsights(bus, sink) }
        // Restore OCO legs from the persistor and reconcile them against venue truth so
        // any sibling whose pair filled during downtime is cancelled before ticks flow.
        pipeline.orderManager.restore(strategies.map { it.first })
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
        if (insightsSink != null &&
            com.qkt.observe.insights.InsightsEventFamily.LIFECYCLE in insightsEvents
        ) {
            val nowTs = clock.now()
            val brokerNames = (builtBrokers.ifEmpty { listOf(broker) }).map { it.name }.distinct()
            for (brokerName in brokerNames) {
                insightsSink.offer(
                    com.qkt.observe.insights.InsightsTranslate.brokerConnected(
                        broker = brokerName,
                        ts = nowTs,
                    ),
                )
            }
            insightsSink.offer(
                com.qkt.observe.insights.InsightsTranslate.marketDataConnected(
                    source = source.name,
                    symbols = feedSymbols,
                    ts = nowTs,
                ),
            )
            if (feed is MarketDataLifecycleFeed) {
                feed.onDisconnect { scope ->
                    insightsSink.offer(
                        com.qkt.observe.insights.InsightsTranslate.marketDataDisconnected(
                            source = scope.source ?: source.name,
                            symbols = scope.symbols ?: feedSymbols,
                            ts = clock.now(),
                            reason = "source-disconnected",
                        ),
                    )
                }
                feed.onReconnect { scope ->
                    insightsSink.offer(
                        com.qkt.observe.insights.InsightsTranslate.marketDataReconnected(
                            source = scope.source ?: source.name,
                            symbols = scope.symbols ?: feedSymbols,
                            ts = clock.now(),
                        ),
                    )
                }
            }
        }

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
            if (broker.supportsPositionTickets) {
                val deployedIds = strategies.map { it.first }
                val tickets = broker.positionTickets()
                for (ticket in tickets) {
                    val owner =
                        ticketAttribution.ownerOf(ticket.ticket)
                            ?: ticketAttribution.fromComment(ticket.comment, deployedIds)
                    if (owner != strategyId) {
                        if (owner == null) {
                            log.error(
                                "flatten skipped unattributed ticket {} on {}; operator intervention required",
                                ticket.ticket,
                                ticket.symbol,
                            )
                        }
                        continue
                    }
                    pipeline.orderManager.cancelPendingForSymbol(ticket.symbol)
                    val side =
                        if (ticket.side == com.qkt.common.Side.BUY) {
                            com.qkt.common.Side.SELL
                        } else {
                            com.qkt.common.Side.BUY
                        }
                    bus.publish(
                        com.qkt.events.OrderEvent(
                            com.qkt.execution.OrderRequest.Market(
                                id = ids.next(),
                                symbol = ticket.symbol,
                                side = side,
                                quantity = ticket.qty,
                                timeInForce = com.qkt.execution.TimeInForce.GTC,
                                timestamp = clock.now(),
                                strategyId = strategyId,
                                closesTicket = ticket.ticket,
                            ),
                        ),
                    )
                }
                return
            }
            for ((symbol, pos) in positions.allPositions()) {
                if (pos.quantity.signum() == 0) continue
                if (broker.positionAccountingMode(symbol) != com.qkt.broker.PositionAccountingMode.NETTING) {
                    log.error(
                        "flatten cannot safely close {} on broker {} without position tickets; " +
                            "accounting mode is {}",
                        symbol,
                        broker.name,
                        broker.positionAccountingMode(symbol),
                    )
                    continue
                }
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
            }.onFailure { n ->
                recordNotificationFailure(strategies.firstOrNull()?.first.orEmpty(), "StrategyError", n)
            }
        }

        fun notifyUnexpectedFeedEnd(reason: String) {
            for ((strategyId, _) in strategies) {
                val notification =
                    when {
                        NotifyEventKind.STRATEGY_STOPPED in notifyEvents ->
                            NotificationEvent.StrategyStopped(
                                strategyId = strategyId,
                                flatten = false,
                                timestamp = clock.now(),
                                unexpected = true,
                                reason = reason,
                            )
                        NotifyEventKind.STRATEGY_ERROR in notifyEvents ->
                            NotificationEvent.StrategyError(
                                strategyId = strategyId,
                                message = reason,
                                timestamp = clock.now(),
                            )
                        else -> null
                    }
                if (notification != null) {
                    runCatching { notifier.notify(notification) }
                        .onFailure { t -> recordNotificationFailure(strategyId, "UnexpectedFeedEnd", t) }
                }
            }
        }

        var alertedPersistenceEpisode = 0L

        fun checkPersistenceHealth() {
            val health = persistor.healthSnapshot()
            if (!health.enabled) return
            val newFailureEpisode = health.failureEpisodes > alertedPersistenceEpisode
            if (!newFailureEpisode && health.consecutiveFailures == 0L) return
            val reason =
                "persistence failure: durable state is stale " +
                    "(failedWrites=${health.failedWrites}, consecutiveFailures=${health.consecutiveFailures}, " +
                    "queueSize=${health.queueSize}, " +
                    "callerRunsTotal=${health.callerRunsTotal})"
            riskState.halt(reason, cancelWorkingOrders = false)
            if (!newFailureEpisode) return
            alertedPersistenceEpisode = health.failureEpisodes
            log.error("{}; blocking new exposure while keeping exits active", reason)
            val ownerStrategyId = strategies.firstOrNull()?.first.orEmpty()
            runCatching {
                notifier.notify(
                    NotificationEvent.StrategyError(
                        strategyId = ownerStrategyId,
                        message = "CRITICAL disk failing — persisted state is stale; new exposure halted",
                        timestamp = clock.now(),
                    ),
                )
            }.onFailure { t -> recordNotificationFailure(ownerStrategyId, "PersistenceFailure", t) }
            if (insightsSink != null &&
                com.qkt.observe.insights.InsightsEventFamily.STATE in insightsEvents
            ) {
                insightsSink.offer(
                    com.qkt.observe.insights.InsightsTranslate.statePersistence(
                        ts = clock.now(),
                        strategyId = ownerStrategyId.takeIf { it.isNotBlank() },
                        health = health,
                    ),
                )
            }
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
                try {
                    var stopDeadlineNanos: Long? = null
                    while (running.get()) {
                        val msg: Inbound? =
                            control.poll()
                                ?: if (stopDeadlineNanos == null) {
                                    tickQueue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                                } else {
                                    control.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                                }
                        if (msg == null) {
                            val deadline = stopDeadlineNanos
                            if (deadline != null && System.nanoTime() >= deadline && control.isEmpty()) {
                                running.set(false)
                            }
                            continue
                        }
                        when (msg) {
                            is Inbound.FeedTick -> processTick(msg)
                            is Inbound.BusEvent ->
                                try {
                                    bus.publish(msg.event)
                                } catch (e: Exception) {
                                    onEngineFault("event ${msg.event::class.simpleName}", e)
                                }
                            is Inbound.Heartbeat ->
                                runCatching {
                                    for (symbol in feedSymbols) marketDataGate.isHealthy(symbol)
                                    pipeline.scheduleHeartbeat(msg.nowMs)
                                }.onFailure { t -> onEngineFault("schedule heartbeat", t) }
                            Inbound.PersistenceHealthCheck -> checkPersistenceHealth()
                            is Inbound.Query -> msg.execute()
                            Inbound.Flatten ->
                                // A failed FLATTEN is the emergency path failing — the loudest case.
                                runCatching { doFlatten() }
                                    .onFailure { t -> onEngineFault("flatten", t) }
                            is Inbound.FeedEnded -> {
                                // Feed ended (finite source drained): process every tick already
                                // queued before stopping, so no tick is dropped.
                                if (!stopping.get()) {
                                    while (true) processTick(tickQueue.poll() ?: break)
                                    if (msg.unexpected) notifyUnexpectedFeedEnd(msg.reason)
                                    running.set(false)
                                }
                            }
                            is Inbound.GracefulStop -> stopDeadlineNanos = msg.deadlineNanos
                        }
                        val deadline = stopDeadlineNanos
                        if (deadline != null && System.nanoTime() >= deadline && control.isEmpty()) {
                            running.set(false)
                        }
                    }
                } catch (e: InterruptedException) {
                    log.info("LiveSession engine thread interrupted")
                    Thread.currentThread().interrupt()
                } finally {
                    running.set(false)
                    // Journal appends run on this thread (bus dispatch), so its channels
                    // close here — the last event is already durable when we count down.
                    runCatching { journal?.close() }
                    runCatching { auditJournal?.close() }
                    terminated.countDown()
                    if (mdcStrategy != null) org.slf4j.MDC.remove("strategy")
                }
            }, "qkt-live-engine")
        thread.isDaemon = true
        // Route every publish from a non-engine thread (broker pollers, WS readers) onto this
        // loop's queue, so subscribers only ever run on the engine thread.
        bus.bindEngineLoop(thread) { ev -> if (running.get()) control.put(Inbound.BusEvent(ev)) }
        control.put(Inbound.PersistenceHealthCheck)
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
                    val lifecycleFeed = feed as? MarketDataLifecycleFeed
                    val unexpected = running.get() && lifecycleFeed?.expectsContinuousDelivery == true
                    val failureReason =
                        lifecycleFeed?.terminalFailureReason()
                            ?: "live market-data feed exceeded its reconnect budget"
                    runCatching { feed.close() }
                    if (insightsSink != null &&
                        com.qkt.observe.insights.InsightsEventFamily.LIFECYCLE in insightsEvents
                    ) {
                        insightsSink.offer(
                            com.qkt.observe.insights.InsightsTranslate.marketDataDisconnected(
                                source = source.name,
                                symbols = feedSymbols,
                                ts = clock.now(),
                                reason = "feed-ended",
                            ),
                        )
                    }
                    // Non-blocking: tell the consumer the feed is done so it drains-then-stops.
                    control.offer(
                        Inbound.FeedEnded(
                            unexpected = unexpected,
                            reason = failureReason,
                        ),
                    )
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
            {
                runCatching { riskState.persistAnchorsIfDirty() }
                runCatching { control.put(Inbound.PersistenceHealthCheck) }
                runCatching { control.put(Inbound.Heartbeat(clock.now())) }
            },
            scheduleHeartbeatIntervalMs,
            scheduleHeartbeatIntervalMs,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )

        // #352: poll real account equity off the engine thread so sizing + drawdown track the
        // broker's account (commissions, swaps, deposits), not just engine-derived PnL. Standalone
        // single-strategy only — allocated portfolio children do not own the whole account.
        // Capability is static: a
        // transiently failed startup read must not disable polling for the entire session. Failed
        // reads retain the last-known value and alert once stale. The network call stays off the consumer.
        val equityPoller: java.util.concurrent.ScheduledExecutorService? =
            if (!usesAllocatedStrategyCapital &&
                equityBasis == LiveEquityBasis.VENUE &&
                strategies.size == 1 &&
                broker.supportsAccountEquity
            ) {
                val monitor =
                    BrokerEquityMonitor(
                        broker = broker,
                        clock = clock,
                        equity = brokerEquity,
                        staleAfterMs = brokerEquityStaleMs,
                        onStale = { failures, staleForMs ->
                            bus.publish(
                                BrokerEvent.AccountEquityStale(
                                    broker = broker.name,
                                    consecutiveFailures = failures,
                                    staleForMs = staleForMs,
                                    timestamp = clock.now(),
                                ),
                            )
                        },
                    )
                java.util.concurrent.Executors
                    .newSingleThreadScheduledExecutor { r ->
                        Thread(r, "qkt-broker-equity-poller").apply { isDaemon = true }
                    }.also { exec ->
                        exec.scheduleAtFixedRate(
                            monitor::tick,
                            0L,
                            brokerEquityPollMs,
                            java.util.concurrent.TimeUnit.MILLISECONDS,
                        )
                    }
            } else {
                null
            }

        // Broker truth → insights: account state, per-ticket positions, and deal history
        // polled on the poller's own thread, off the engine loop. Replaces the retired
        // engine-thread ledger snapshots — dashboards read state.* / broker.deal now.
        val brokerStatePoller =
            if (insightsSink != null &&
                com.qkt.observe.insights.InsightsEventFamily.STATE in insightsEvents &&
                builtBrokers.isNotEmpty()
            ) {
                com.qkt.observe.insights
                    .BrokerStatePoller(
                        brokers = builtBrokers.toList(),
                        sink = insightsSink,
                        attribution = ticketAttribution,
                        deployedIds = { strategies.map { it.first } },
                        pollIntervalMs = insightsStatePollMs,
                        backfillDays = insightsDealBackfillDays,
                        emitDeals = com.qkt.observe.insights.InsightsEventFamily.DEAL in insightsEvents,
                    ).also { it.start() }
            } else {
                null
            }

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
                }.onFailure { t -> recordNotificationFailure(strategyId, "StrategyStarted", t) }
            }
        }
        if (insightsSink != null &&
            com.qkt.observe.insights.InsightsEventFamily.LIFECYCLE in insightsEvents
        ) {
            for ((strategyId, _) in strategies) {
                insightsSink.offer(
                    com.qkt.observe.insights.InsightsTranslate.strategyStarted(
                        strategyId = strategyId,
                        ts = clock.now(),
                        metadata = insightsStrategyMetadata[strategyId].orEmpty(),
                    ),
                )
            }
        }

        fun <T> engineSnapshot(read: () -> T): T {
            if (Thread.currentThread() === thread || !thread.isAlive) return read()
            val result = java.util.concurrent.CompletableFuture<T>()
            control.put(
                Inbound.Query {
                    runCatching(read)
                        .onSuccess(result::complete)
                        .onFailure(result::completeExceptionally)
                },
            )
            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENGINE_QUERY_TIMEOUT_MS)
            while (thread.isAlive) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) {
                    throw IllegalStateException("live engine did not produce a consistent snapshot within the timeout")
                }
                try {
                    return result.get(
                        minOf(remainingNs, TimeUnit.MILLISECONDS.toNanos(QUEUE_POLL_MS)),
                        TimeUnit.NANOSECONDS,
                    )
                } catch (_: java.util.concurrent.TimeoutException) {
                    // Recheck thread liveness so a finite feed cannot strand the query on shutdown.
                }
            }
            return if (result.isDone) {
                result.get()
            } else {
                check(terminated.await(0L, TimeUnit.MILLISECONDS)) {
                    "live engine stopped without publishing its termination barrier"
                }
                // CountDownLatch establishes a happens-before edge from the engine's final
                // mutation to this read. The engine is terminated, so no concurrent writer exists.
                read()
            }
        }

        return object : LiveSessionHandle {
            override val running: Boolean get() = running.get()

            override val droppedTicks: Long
                get() =
                    (if (feed is LiveTickFeed) feed.droppedTicks.get() else 0L) +
                        droppedInboundTicks.get() +
                        pipeline.droppedLateTicks()

            override fun inboundQueueDepth(): Int = control.size + tickQueue.size

            override fun staleSymbols(): Map<String, Long> = marketDataGate.staleSymbols()

            override fun clockSkewedSymbols(): Map<String, Long> = marketDataGate.clockSkewedSymbols()

            override fun persistenceHealth(): PersistenceHealth = persistor.healthSnapshot()

            override fun reconcile(): ReconcileReport {
                val ownerId = strategies.firstOrNull()?.first.orEmpty()
                val engineState =
                    engineSnapshot {
                        strategyPositions.allLegsFor(ownerId) to strategyPnL.equityFor(ownerId)
                    }
                // positionTickets() carries the venue ticket, so the broker side can be scoped
                // to this strategy by attribution and keyed identically to the engine tracker.
                // getOpenPositions() is magic-global and ticketless, which made the old diff
                // double-count (prefixed vs bare key) and cry wolf on a shared account (#413).
                var brokerReadError: String? = null
                val brokerTickets =
                    try {
                        broker.positionTickets()
                    } catch (e: Exception) {
                        brokerReadError = e.message ?: e::class.simpleName ?: "unknown broker read failure"
                        emptyList()
                    }
                val accountingModes =
                    symbols.associate { symbol ->
                        symbol.substringAfter(":") to broker.positionAccountingMode(symbol)
                    }
                return ReconcileReport(
                    deltas =
                        reconcileDeltas(
                            ownerId,
                            brokerTickets,
                            ticketAttribution,
                            engineState.first,
                            accountingModes,
                        ),
                    engineEquity = engineState.second,
                    brokerEquity = runCatching { broker.accountEquity() }.getOrNull(),
                    protectionDeltas = reconcileProtectionDeltas(ownerId, brokerTickets, ticketAttribution),
                    brokerReadFailed = brokerReadError != null,
                    brokerReadError = brokerReadError,
                )
            }

            override fun stop() {
                if (!stopping.compareAndSet(false, true)) return
                feedThread.interrupt()
                runCatching { feed.close() }
                runCatching { brokerStatePoller?.close() }
                // Stop the schedule heartbeat thread so it doesn't outlive the session.
                runCatching {
                    scheduleHeartbeat.shutdownNow()
                    scheduleHeartbeat.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                // Stop the broker-equity poller (#352) so it doesn't outlive the session.
                runCatching { equityPoller?.shutdownNow() }
                tickQueue.clear()
                val drainGraceMs = if (builtBrokers.isEmpty()) 0L else STOP_DRAIN_GRACE_MS
                control.put(
                    Inbound.GracefulStop(
                        deadlineNanos = System.nanoTime() + drainGraceMs * 1_000_000L,
                    ),
                )
                if (!terminated.await(drainGraceMs + 500L, TimeUnit.MILLISECONDS)) {
                    running.set(false)
                    thread.interrupt()
                }
                // Release venue-side lifecycle resources (MT5 pollers, Bybit reconcilers)
                // so a long-running daemon cycling strategies doesn't accumulate threads.
                for (b in builtBrokers) runCatching { b.shutdown() }
                runCatching { riskState.persistAnchorsIfDirty() }
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
                        }.onFailure { t -> recordNotificationFailure(strategyId, "StrategyStopped", t) }
                    }
                }
                if (insightsSink != null &&
                    com.qkt.observe.insights.InsightsEventFamily.LIFECYCLE in insightsEvents
                ) {
                    for ((strategyId, _) in strategies) {
                        insightsSink.offer(
                            com.qkt.observe.insights.InsightsTranslate.strategyStopped(
                                strategyId = strategyId,
                                ts = clock.now(),
                                flatten = false,
                            ),
                        )
                    }
                }
            }

            override fun awaitTermination(timeout: Duration): Boolean =
                terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

            override fun recentTrades(): List<Trade> = trades.snapshot()

            override fun positionsFor(strategyId: String): List<com.qkt.positions.Position> =
                engineSnapshot { strategyPositions.positionsFor(strategyId).values.toList() }

            override fun dailySummaryRows(): List<StrategySummary> =
                engineSnapshot { this@LiveSession.dailySummaryRows(strategyPnL, strategyPositions) }

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

            override fun haltReason(): String? = riskState.haltReason

            override fun haltScope(): com.qkt.risk.HaltScope? = riskState.globalHaltScope()

            override fun resume() {
                riskState.resume()
            }

            override fun isHalted(): Boolean = riskState.halted

            override fun flattenAndVerify(timeout: Duration): FlattenResult {
                val strategyId =
                    strategies.firstOrNull()?.first
                        ?: return FlattenResult(false, detail = "session has no strategy owner")
                if (!broker.supportsPositionTickets) {
                    flatten()
                    return FlattenResult(
                        verifiedFlat = false,
                        detail = "broker ${broker.name} cannot verify open position tickets",
                    )
                }
                val deployedIds = strategies.map { it.first }

                fun targetTickets(): Pair<List<com.qkt.broker.BrokerPositionTicket>, List<String>> {
                    val tickets = broker.positionTickets()
                    val owned = mutableListOf<com.qkt.broker.BrokerPositionTicket>()
                    val ambiguous = mutableListOf<String>()
                    for (ticket in tickets) {
                        val owner =
                            ticketAttribution.ownerOf(ticket.ticket)
                                ?: ticketAttribution.fromComment(ticket.comment, deployedIds)
                        when (owner) {
                            strategyId -> owned.add(ticket)
                            null -> ambiguous.add(ticket.ticket)
                        }
                    }
                    return owned to ambiguous
                }

                val initial =
                    runCatching { targetTickets() }
                        .getOrElse { error ->
                            return FlattenResult(false, detail = "broker position read failed: ${error.message}")
                        }
                for (ticket in initial.first) {
                    val side =
                        if (ticket.side == com.qkt.common.Side.BUY) {
                            com.qkt.common.Side.SELL
                        } else {
                            com.qkt.common.Side.BUY
                        }
                    val ack =
                        runCatching {
                            broker.submit(
                                com.qkt.execution.OrderRequest.Market(
                                    id = "operator-kill-${ticket.ticket}",
                                    symbol = ticket.symbol,
                                    side = side,
                                    quantity = ticket.qty,
                                    timeInForce = com.qkt.execution.TimeInForce.GTC,
                                    timestamp = clock.now(),
                                    strategyId = strategyId,
                                    closesTicket = ticket.ticket,
                                ),
                            )
                        }.getOrElse { error ->
                            return FlattenResult(
                                false,
                                remainingTickets = (initial.first.map { it.ticket } + initial.second).distinct(),
                                detail = "close submission failed for ticket ${ticket.ticket}: ${error.message}",
                            )
                        }
                    if (!ack.accepted) {
                        return FlattenResult(
                            false,
                            remainingTickets = (initial.first.map { it.ticket } + initial.second).distinct(),
                            detail = "close rejected for ticket ${ticket.ticket}: ${ack.rejectReason}",
                        )
                    }
                }

                val deadline = System.nanoTime() + timeout.toNanos()
                var lastRemaining = (initial.first.map { it.ticket } + initial.second).distinct()
                while (true) {
                    val current =
                        runCatching { targetTickets() }
                            .getOrElse { error ->
                                return FlattenResult(
                                    false,
                                    remainingTickets = lastRemaining,
                                    detail = "broker verification failed: ${error.message}",
                                )
                            }
                    val remaining = (current.first.map { it.ticket } + current.second).distinct()
                    lastRemaining = remaining
                    if (remaining.isEmpty()) return FlattenResult(verifiedFlat = true)
                    if (System.nanoTime() >= deadline) {
                        return FlattenResult(
                            verifiedFlat = false,
                            remainingTickets = remaining,
                            detail = "broker still reports open or unattributed position tickets",
                        )
                    }
                    try {
                        Thread.sleep(FLATTEN_VERIFY_POLL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return FlattenResult(
                            verifiedFlat = false,
                            remainingTickets = remaining,
                            detail = "broker verification interrupted",
                        )
                    }
                }
            }

            // Legacy fire-and-forget flatten stays engine-thread confined for internal callers.
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

    class Query(
        val execute: () -> Unit,
    ) : Inbound

    object Flatten : Inbound

    object PersistenceHealthCheck : Inbound

    data class FeedEnded(
        val unexpected: Boolean,
        val reason: String,
    ) : Inbound

    data class GracefulStop(
        val deadlineNanos: Long,
    ) : Inbound
}
