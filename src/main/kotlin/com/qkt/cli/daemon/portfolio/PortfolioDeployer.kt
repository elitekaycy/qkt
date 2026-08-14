package com.qkt.cli.daemon.portfolio

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.cli.daemon.PortfolioRecord
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.daemon.buildSnapshot
import com.qkt.cli.daemon.signalToJson
import com.qkt.cli.daemon.tradeToJson
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.WhenRun
import com.qkt.dsl.portfolio.CompiledChild
import com.qkt.dsl.portfolio.PortfolioCompiled
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.dsl.portfolio.capitalAllocations
import com.qkt.marketdata.source.MarketSource
import com.qkt.notify.NoopNotifier
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds a [PortfolioRecord] from a compiled portfolio AST: spawns each child
 * [StrategyHandle], wires them through [ChildHandle] gates, hands the bundle to a
 * [PortfolioSupervisor], and starts the supervisor running.
 *
 * Atomic: if any child fails to deploy, every already-deployed child is closed
 * before the exception propagates — the daemon never ends up with a half-deployed
 * portfolio. The daemon's strategy registry receives the children only when the
 * full deploy succeeds.
 */
class PortfolioDeployer(
    private val stateDir: StateDir,
    private val marketSourceProvider: (List<String>) -> MarketSource,
    private val brokerFactories: Map<String, com.qkt.app.BrokerFactory> = emptyMap(),
    private val instrumentRegistry: com.qkt.instrument.InstrumentRegistry? = null,
    private val ringSize: Int = 1000,
    private val bind: String = "127.0.0.1",
    /**
     * Daemon-level daily-loss cap shared across every child of every portfolio. Set to
     * `BigDecimal.ZERO` to disable. Default is [com.qkt.cli.Config.DEFAULT_MAX_DAILY_LOSS].
     */
    private val maxDailyLoss: java.math.BigDecimal = com.qkt.cli.Config.DEFAULT_MAX_DAILY_LOSS,
    /**
     * Book-level (account-wide) drawdown halts (#351). Fractions; null disables. The book's basis is
     * the portfolio `CAPITAL`; on breach the aggregator flattens + halts every child.
     */
    private val maxDrawdownPct: java.math.BigDecimal? = null,
    private val maxDailyDrawdownPct: java.math.BigDecimal? = null,
    private val totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
    private val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
    private val riskIntervalMs: Long = 1000L,
    private val bookRiskConfig: com.qkt.risk.book.BookRiskConfig? = null,
    private val perStrategyRisk: Map<String, com.qkt.cli.PerStrategyRisk> = emptyMap(),
    private val accountingConfig: com.qkt.accounting.AccountingConfig = com.qkt.accounting.AccountingConfig(),
    private val liveEquityBasis: com.qkt.app.LiveEquityBasis = com.qkt.app.LiveEquityBasis.VENUE,
    private val maxOrderQty: java.math.BigDecimal =
        com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
    private val maxOrderNotional: java.math.BigDecimal =
        com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
    private val priceCollarFrac: java.math.BigDecimal =
        com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
    private val runawayMaxRoundTrips: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS,
    private val runawayMaxRejections: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS,
    private val marginFloorPct: java.math.BigDecimal = java.math.BigDecimal("200"),
    private val measuredUsageHours: Long = 0L,
    private val measuredUsageMaxQty: java.math.BigDecimal =
        com.qkt.risk.rules.MeasuredUsage.DEFAULT_MEASURED_MAX_QTY,
    private val clock: com.qkt.common.Clock = com.qkt.common.SystemClock(),
    private val calendar: com.qkt.common.TradingCalendar =
        com.qkt.common.TradingCalendar
            .fxDefault(),
    /** Resolves the live calendar per routed qkt symbol; null retains [calendar]. */
    private val calendarFor: ((String) -> com.qkt.common.TradingCalendar)? = null,
    private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
    /**
     * Append-only order-event journal root. When non-null, every portfolio child writes
     * its order lifecycle (including risk rejections) to `<root>/<strategy>/journal-*.jsonl`.
     * Must match the journal wiring used by standalone [com.qkt.cli.daemon.StrategyHandle]
     * so portfolio children have the same audit trail.
     */
    private val journalRoot: java.nio.file.Path? = null,
    /**
     * Append-only all-event engine audit journal root. When non-null, every portfolio child
     * writes its full event stream to `<root>/<strategy>/audit-*.jsonl`.
     */
    private val auditJournalRoot: java.nio.file.Path? = null,
    /** Telegram alert sink shared across every portfolio child. Default discards events. */
    private val notifier: Notifier = NoopNotifier,
    private val notifyEvents: Set<NotifyEventKind> = emptySet(),
    /** Insights egress sink shared across every portfolio child; null disables. */
    private val insightsSink: com.qkt.observe.insights.InsightsSink? = null,
    private val insightsEvents: Set<com.qkt.observe.insights.InsightsEventFamily> = emptySet(),
    private val insightsDeployedIds: () -> Collection<String> = { emptyList() },
    private val insightsStatePollMs: Long = 10_000L,
    private val insightsDealBackfillDays: Long = 30L,
    /** Market-data gate thresholds (the `market_data:` config block) passed to every child session. */
    private val marketDataGateConfig: com.qkt.marketdata.MarketDataGateConfig =
        com.qkt.marketdata.MarketDataGateConfig.DEFAULT,
) {
    /**
     * Deploy a compiled portfolio and start its supervisor. Throws if any child
     * fails to come up — partial deploys are torn down before the exception propagates.
     */
    fun deploy(
        portfolioName: String,
        compiled: PortfolioCompiled,
    ): PortfolioRecord {
        val children = mutableListOf<StrategyHandle>()
        val childWrappers = mutableListOf<ChildHandle>()
        try {
            val allocations = capitalAllocations(compiled.ast)
            val bookCapital = bookRiskConfig?.capital ?: compiled.ast.capital
            require(bookRiskConfig == null || bookCapital != null) {
                "book_risk is configured but neither book_risk.capital nor portfolio CAPITAL is set"
            }
            require((maxDrawdownPct == null && maxDailyDrawdownPct == null) || bookCapital != null) {
                "portfolio drawdown limits require portfolio CAPITAL or book_risk.capital"
            }
            val bookWindow =
                compiled.ast.streams
                    .firstOrNull()
                    ?.timeframe
                    ?.let(TimeWindow::parse)
            val bookAnnualization = bookAnnualization(compiled)
            val effectiveBookRiskConfig =
                if (compiled.ast.allocate != null && bookCapital != null) {
                    val base =
                        bookRiskConfig ?: com.qkt.risk.book
                            .BookRiskConfig()
                    val rebalanceBars =
                        compiled.ast.allocate.rebalanceEveryDurationMs?.let { durationMs ->
                            bookWindow?.let { window ->
                                java.math
                                    .BigDecimal(durationMs)
                                    .divide(java.math.BigDecimal(window.durationMs), 0, java.math.RoundingMode.CEILING)
                                    .toInt()
                            } ?: 0
                        } ?: 0
                    base.copy(
                        capital = base.capital ?: bookCapital,
                        allocation =
                            com.qkt.risk.book.Allocation(
                                method = com.qkt.risk.book.AllocationMethod.REGIME_WEIGHTED,
                                rebalanceEveryBars = rebalanceBars,
                            ),
                    )
                } else {
                    bookRiskConfig
                }
            val bookController =
                if (effectiveBookRiskConfig != null && bookCapital != null) {
                    com.qkt.risk.book
                        .BookRiskController(effectiveBookRiskConfig, bookCapital, bookAnnualization)
                } else {
                    null
                }
            val bookRiskEnabled =
                bookController != null ||
                    maxDrawdownPct != null ||
                    maxDailyDrawdownPct != null ||
                    maxDailyLoss.signum() > 0
            val bookFillBuffer = if (bookRiskEnabled) PortfolioRiskFillBuffer() else null
            val bookBalance = compiled.ast.capital?.let { PortfolioBookBalance(it) }
            for (compiledChild in compiled.children) {
                val (handle, wrapper) =
                    createChild(
                        portfolioName,
                        compiled,
                        compiledChild,
                        allocations[compiledChild.alias],
                        bookController,
                        bookFillBuffer?.let { buffer -> buffer::record } ?: { _, _ -> },
                        bookBalance,
                    )
                children.add(handle)
                childWrappers.add(wrapper)
            }
            // Bind before the supervisor activates any gate: children captured the view at
            // construction and no entry can size until activation.
            bookBalance?.bind(
                compiled.children.zip(childWrappers).map { (child, wrapper) ->
                    {
                        wrapper.handle.live
                            .pnlSnapshot(child.strategyId)
                            .realized
                    }
                },
            )
            val symbols =
                compiled.ast.streams
                    .map { it.qktSymbol }
                    .distinct()
            val hasConditionalRules =
                compiled.ast.rules.any { it is WhenRun } || compiled.ast.regimes != null
            val portfolioGate =
                PortfolioGate(
                    ast = compiled.ast,
                    clock = clock,
                    calendar = calendarFor?.invoke(symbols.firstOrNull() ?: "") ?: calendar,
                ).also { it.prepare() }
            val riskAggregator =
                buildRiskAggregator(
                    portfolioName,
                    compiled,
                    childWrappers,
                    bookController,
                    portfolioGate,
                    sampleOnEvaluate = bookWindow == null,
                    portfolioInsights = insightsSink,
                )
            if (riskAggregator != null) bookFillBuffer?.bind(riskAggregator)
            val supervisor =
                PortfolioSupervisor(
                    ast = compiled.ast,
                    children = childWrappers,
                    marketSource =
                        if (symbols.isEmpty() ||
                            (!hasConditionalRules && bookController == null)
                        ) {
                            null
                        } else {
                            marketSourceProvider(symbols)
                        },
                    riskAggregator = riskAggregator,
                    riskIntervalMs = riskIntervalMs,
                    gate = portfolioGate,
                )
            supervisor.start()

            // Book telemetry is enqueued asynchronously; no network work runs on a child engine.
            insightsSink?.offer(
                com.qkt.observe.insights.InsightsTranslate.portfolioConfigured(
                    portfolioName,
                    clock.now(),
                    bookCapital,
                ),
            )
            if (allocations.isNotEmpty()) {
                insightsSink?.offer(
                    com.qkt.observe.insights.InsightsTranslate.portfolioAllocationUpdated(
                        portfolioName,
                        clock.now(),
                        allocations.mapKeys { (alias, _) -> "$portfolioName:$alias" },
                    ),
                )
            }

            val portfolioLog = stateDir.logFile(portfolioName)
            Files.createDirectories(portfolioLog.parent)
            if (!Files.exists(portfolioLog)) Files.createFile(portfolioLog)

            return PortfolioRecord(
                name = portfolioName,
                version = compiled.ast.version,
                supervisor = supervisor,
                children = children,
                logFile = portfolioLog,
                startedAt = Instant.now(),
            )
        } catch (e: Exception) {
            for (h in children) runCatching { h.close() }
            throw e
        }
    }

    /**
     * Build the book-level drawdown aggregator, or null when there's nothing to enforce (no
     * portfolio `CAPITAL` to form a basis, or no drawdown config). Reads each child's live PnL
     * snapshot and acts via its `flatten`/`halt`. [compiled.children] and [wrappers] are built in
     * the same order in [deploy], so `zip` pairs each child's strategyId with its handle.
     */
    private fun buildRiskAggregator(
        portfolioName: String,
        compiled: PortfolioCompiled,
        wrappers: List<ChildHandle>,
        bookController: com.qkt.risk.book.BookRiskController?,
        portfolioGate: PortfolioGate,
        sampleOnEvaluate: Boolean,
        portfolioInsights: com.qkt.observe.insights.InsightsSink?,
    ): PortfolioRiskAggregator? {
        val capital = compiled.ast.capital
        val controllerCapital = bookRiskConfig?.capital ?: capital
        if (bookController == null &&
            maxDrawdownPct == null &&
            maxDailyDrawdownPct == null &&
            maxDailyLoss.signum() <= 0
        ) {
            return null
        }
        val riskCapital = controllerCapital ?: java.math.BigDecimal.ZERO

        val pnlSources: List<() -> com.qkt.app.SessionPnl> =
            compiled.children.zip(wrappers).map { (child, w) ->
                { w.handle.live.pnlSnapshot(child.strategyId) }
            }
        val targets: List<ChildRiskTarget> =
            wrappers.map { w ->
                object : ChildRiskTarget {
                    override fun flatten() = w.handle.live.flatten()

                    override fun halt(
                        reason: String,
                        scope: com.qkt.risk.HaltScope,
                    ) = w.handle.live.halt(reason, scope)

                    override fun resume() = w.handle.live.resume()

                    override fun isHalted(): Boolean = w.handle.live.isHalted()

                    override fun haltReason(): String? = w.handle.live.haltReason()

                    override fun haltScope(): com.qkt.risk.HaltScope? = w.handle.live.haltScope()
                }
            }
        val bookRiskState =
            com.qkt.risk.RiskState(
                BookPnLProvider(pnlSources),
                com.qkt.pnl.StrategyPnL(
                    com.qkt.positions.StrategyPositionTracker(),
                    com.qkt.marketdata.MarketPriceTracker(),
                ),
                clock,
                com.qkt.bus.EventBus(clock, com.qkt.common.MonotonicSequenceGenerator()),
                riskCapital,
                dailyDdBasis,
                persist = { state -> persistor.saveRiskState("$PORTFOLIO_RISK_PREFIX$portfolioName", state) },
            )
        persistor.loadRiskState("$PORTFOLIO_RISK_PREFIX$portfolioName")?.let(bookRiskState::restore)
        val haltRules =
            buildList {
                if (maxDailyLoss.signum() > 0) {
                    add(
                        com.qkt.risk.rules
                            .MaxDailyLoss(maxDailyLoss),
                    )
                }
                maxDrawdownPct?.let {
                    add(
                        com.qkt.risk.rules
                            .MaxDrawdown(it, totalDdBasis, riskCapital),
                    )
                }
                maxDailyDrawdownPct?.let {
                    add(
                        com.qkt.risk.rules
                            .MaxDailyDrawdown(it),
                    )
                }
            }
        val childPairs = compiled.children.zip(wrappers)
        val aliasToStrategyId = compiled.children.associate { it.alias to it.strategyId }
        return PortfolioRiskAggregator(
            children = targets,
            bookRiskState = bookRiskState,
            haltRules = haltRules,
            clock = clock,
            onSample = { timestamp ->
                val controller = bookController ?: return@PortfolioRiskAggregator
                val legs =
                    childPairs.flatMap { (child, wrapper) ->
                        wrapper.handle.live.bookLegs(child.strategyId)
                    }
                val pnlSnapshots =
                    childPairs.associate { (child, wrapper) ->
                        child.strategyId to wrapper.handle.live.pnlSnapshot(child.strategyId)
                    }
                val perStrategyPnl =
                    pnlSnapshots.mapValues { (_, pnl) -> pnl.realized.add(pnl.unrealized) }
                val realized =
                    pnlSnapshots.values.fold(java.math.BigDecimal.ZERO) { total, pnl ->
                        total.add(pnl.realized)
                    }
                val unrealized =
                    pnlSnapshots.values.fold(java.math.BigDecimal.ZERO) { total, pnl ->
                        total.add(pnl.unrealized)
                    }
                val equity =
                    riskCapital.add(
                        perStrategyPnl.values.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
                    )
                portfolioInsights?.offer(
                    com.qkt.observe.insights.InsightsTranslate.portfolioEquityUpdated(
                        portfolioName,
                        timestamp,
                        equity,
                        realized,
                        unrealized,
                        perStrategyPnl,
                    ),
                )
                val regimeWeights =
                    portfolioGate.currentState().weightByAlias.mapKeys { (alias, _) ->
                        aliasToStrategyId[alias] ?: alias
                    }
                controller.setRegimeWeights(regimeWeights)
                controller.onSample(
                    com.qkt.risk.book.BookSnapshot(
                        timestamp,
                        equity,
                        com.qkt.risk.book
                            .bookExposure(legs, timestampMs = timestamp),
                        perStrategyPnl,
                    ),
                )
            },
            sampleOnEvaluate = sampleOnEvaluate,
        )
    }

    private fun childInsightsMetadata(
        portfolioName: String,
        compiled: PortfolioCompiled,
        compiledChild: CompiledChild,
        allocatedCapital: java.math.BigDecimal?,
        perStrategyOverride: com.qkt.cli.PerStrategyRisk?,
    ): Map<String, Any?> {
        val weights =
            compiled.ast.rules.associate { rule ->
                when (rule) {
                    is WhenRun -> rule.alias to rule.weight
                    is AlwaysRun -> rule.alias to rule.weight
                }
            }
        return linkedMapOf(
            "kind" to "portfolio_child",
            "deployName" to "$portfolioName/${compiledChild.alias}",
            "strategyId" to compiledChild.strategyId,
            "dslName" to compiledChild.ast.name,
            "portfolioId" to portfolioName,
            "portfolioName" to compiled.ast.name,
            "portfolioAlias" to compiledChild.alias,
            "portfolioHold" to compiledChild.hold,
            "portfolioWeight" to weights[compiledChild.alias],
            "portfolioCapital" to compiled.ast.capital,
            "allocatedCapital" to allocatedCapital,
            "dslVersion" to compiledChild.ast.version,
            "runtimeMode" to if (brokerFactories.isEmpty()) "paper" else "live",
            "brokers" to brokerFactories.keys.sorted(),
            "symbols" to compiledChild.symbols,
            "streams" to
                compiledChild.ast.streams.map {
                    linkedMapOf(
                        "alias" to it.alias,
                        "broker" to it.broker,
                        "symbol" to it.symbol,
                        "qktSymbol" to it.qktSymbol,
                        "timeframe" to it.timeframe,
                        "warmupBars" to it.warmupBars,
                    )
                },
            "risk" to
                linkedMapOf(
                    "maxDailyLoss" to maxDailyLoss,
                    "maxDrawdownPct" to maxDrawdownPct,
                    "maxDailyDrawdownPct" to maxDailyDrawdownPct,
                    "totalDdBasis" to totalDdBasis.name,
                    "dailyDdBasis" to dailyDdBasis.name,
                    "runawayMaxRoundTrips10m" to runawayMaxRoundTrips,
                    "runawayMaxBrokerRejections1m" to runawayMaxRejections,
                    "perStrategyMaxDailyLoss" to perStrategyOverride?.maxDailyLoss,
                    "perStrategyMaxPositionSize" to perStrategyOverride?.maxPositionSize,
                    "perStrategyMaxOpenPositions" to perStrategyOverride?.maxOpenPositions,
                    "perStrategyMaxDrawdownPct" to perStrategyOverride?.maxDrawdownPct,
                    "perStrategyMaxDailyDrawdownPct" to perStrategyOverride?.maxDailyDrawdownPct,
                    "perStrategyMaxTradesPerDay" to perStrategyOverride?.maxTradesPerDay,
                    "perStrategyCooldownAfterLossMs" to perStrategyOverride?.cooldownAfterLossMs,
                    "perStrategyCooldownAfterLossAfterConsecutive" to
                        perStrategyOverride?.cooldownAfterLossAfterConsecutive,
                    "perStrategyLossStreakHalt" to perStrategyOverride?.lossStreakHalt,
                    "perStrategyLossStreakHaltScope" to perStrategyOverride?.lossStreakHaltScope?.name,
                ),
        )
    }

    private fun createChild(
        portfolioName: String,
        compiled: PortfolioCompiled,
        compiledChild: CompiledChild,
        allocatedCapital: java.math.BigDecimal? = null,
        bookController: com.qkt.risk.book.BookRiskController? = null,
        onBookRealized: (String, java.math.BigDecimal) -> Unit = { _, _ -> },
        bookBalance: PortfolioBookBalance? = null,
    ): Pair<StrategyHandle, ChildHandle> {
        val childName = "$portfolioName/${compiledChild.alias}"
        val gateActive = AtomicBoolean(false)
        val operatorStop = AtomicBoolean(false)
        val effectiveActive: () -> Boolean = { gateActive.get() && !operatorStop.get() }

        val symbols =
            compiledChild.ast.streams
                .map { it.qktSymbol }
                .distinct()
        val feedSymbols = (symbols + accountingConfig.normalizedSymbols.values).distinct()
        val source = marketSourceProvider(feedSymbols)
        val ring = EventRing(capacity = ringSize)
        val startMs = System.currentTimeMillis()
        val startedAt = Instant.ofEpochMilli(startMs)

        val candleWindow: TimeWindow? =
            compiledChild.ast.streams
                .firstOrNull()
                ?.timeframe
                ?.let { TimeWindow.parse(it) }
        val childCalendar = symbols.firstOrNull()?.let(::calendarForSymbol) ?: calendar

        // Match the shared-account portfolio backtest: this cap is book-wide, not N
        // independent child budgets that multiply the configured loss limit.
        val haltRules: List<com.qkt.risk.HaltRule> = emptyList()
        val perStrategyOverride = perStrategyRisk[compiledChild.strategyId]
        val session =
            LiveSession(
                strategies = listOf(compiledChild.strategyId to compiledChild.compiled),
                haltRules = haltRules,
                source = source,
                symbols = compiledChild.symbols,
                feedSymbols = feedSymbols,
                candleWindow = candleWindow,
                clock = clock,
                calendar = childCalendar,
                accountingConfig = accountingConfig,
                equityBasis = liveEquityBasis,
                // Insights and trading events must use the same canonical child id.
                // The filename discriminator still maps ':' to '__' for local logs.
                mdcStrategy = compiledChild.strategyId,
                onTrade = { trade, realized, strategyId ->
                    onBookRealized(strategyId, realized)
                    com.qkt.cli.daemon.logging.withMdc("strategy", compiledChild.strategyId) {
                        com.qkt.cli.daemon.logging.withMdc("parent", portfolioName) {
                            ring.append("trade", tradeToJson(trade, realized))
                            com.qkt.cli.daemon.TradeLog
                                .emit(trade, realized)
                        }
                    }
                },
                onSignal = { sig ->
                    com.qkt.cli.daemon.logging.withMdc("strategy", compiledChild.strategyId) {
                        com.qkt.cli.daemon.logging.withMdc("parent", portfolioName) {
                            ring.append("signal", signalToJson(sig))
                        }
                    }
                },
                gate = effectiveActive,
                bookRiskController = bookController,
                brokerFactories = brokerFactories,
                instrumentRegistry = instrumentRegistry,
                persistor = persistor,
                journal =
                    journalRoot?.let {
                        com.qkt.observe.OrderJournal(it, com.qkt.common.SystemClock())
                    },
                auditJournal =
                    auditJournalRoot?.let {
                        com.qkt.observe.EngineAuditJournal(it, compiledChild.strategyId, com.qkt.common.SystemClock())
                    },
                notifier = notifier,
                notifyEvents = notifyEvents,
                insightsSink = insightsSink,
                insightsEvents = insightsEvents,
                insightsDeployedIds = insightsDeployedIds,
                insightsStatePollMs = insightsStatePollMs,
                insightsDealBackfillDays = insightsDealBackfillDays,
                marketDataGateConfig = marketDataGateConfig,
                insightsStrategyMetadata =
                    mapOf(
                        compiledChild.strategyId to
                            childInsightsMetadata(
                                portfolioName = portfolioName,
                                compiled = compiled,
                                compiledChild = compiledChild,
                                allocatedCapital = allocatedCapital,
                                perStrategyOverride = perStrategyOverride,
                            ),
                    ),
                perStrategyMaxDailyLoss = perStrategyOverride?.maxDailyLoss,
                perStrategyMaxPositionSize = perStrategyOverride?.maxPositionSize,
                perStrategyMaxOpenPositions = perStrategyOverride?.maxOpenPositions,
                perStrategyMaxDrawdownPct = perStrategyOverride?.maxDrawdownPct,
                perStrategyMaxDailyDrawdownPct = perStrategyOverride?.maxDailyDrawdownPct,
                perStrategyMaxTradesPerDay = perStrategyOverride?.maxTradesPerDay,
                perStrategyCooldownAfterLossMs = perStrategyOverride?.cooldownAfterLossMs,
                perStrategyCooldownAfterLossAfterConsecutive =
                    perStrategyOverride?.cooldownAfterLossAfterConsecutive ?: 1,
                perStrategyLossStreakHalt = perStrategyOverride?.lossStreakHalt,
                perStrategyLossStreakHaltScope =
                    perStrategyOverride?.lossStreakHaltScope ?: com.qkt.risk.HaltScope.PERSISTENT,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                priceCollarFrac = priceCollarFrac,
                runawayMaxRoundTrips = runawayMaxRoundTrips,
                runawayMaxRejections = runawayMaxRejections,
                marginFloorPct = marginFloorPct,
                measuredUsageHours = measuredUsageHours,
                measuredUsageMaxQty = measuredUsageMaxQty,
                startingBalances =
                    allocatedCapital?.let { mapOf(compiledChild.strategyId to it) } ?: emptyMap(),
                bookBalance = bookBalance,
            ).start()

        val server =
            ObservabilityServer(
                ring = ring,
                statusProvider = {
                    val layers =
                        session.pendingStackLayerInfos().map {
                            PendingStackLayer(
                                stackId = it.stackId,
                                layer = it.layer,
                                triggerPrice = it.triggerPrice,
                                side = it.side,
                                quantity = it.quantity,
                            )
                        }
                    buildSnapshot(
                        childName,
                        compiledChild.ast.version,
                        startMs,
                        startedAt.toString(),
                        session.recentTrades(),
                        layers,
                        pnl = session.pnlSnapshot(compiledChild.strategyId),
                        persistenceHealth = session.persistenceHealth(),
                    )
                },
                running = { session.running },
                onStop = { _ -> session.stop() },
                bind = bind,
                port = 0,
                latencyProvider = { session.latencySnapshot() },
            ).also { it.start() }

        val logFile = stateDir.logFile(childName)
        Files.createDirectories(logFile.parent)
        if (!Files.exists(logFile)) Files.createFile(logFile)

        val handle =
            StrategyHandle(
                name = childName,
                ast = compiledChild.ast,
                live = session,
                observability = server,
                ring = ring,
                logFile = logFile,
                startedAt = startedAt,
                childMeta =
                    StrategyHandle.ChildMeta(
                        parent = portfolioName,
                        alias = compiledChild.alias,
                        hold = compiledChild.hold,
                        gateActive = gateActive,
                        operatorStop = operatorStop,
                    ),
            )
        val wrapper =
            ChildHandle(
                parent = portfolioName,
                alias = compiledChild.alias,
                hold = compiledChild.hold,
                handle = handle,
                gateActive = gateActive,
                operatorStop = operatorStop,
            )
        return handle to wrapper
    }

    private fun calendarForSymbol(qktSymbol: String): com.qkt.common.TradingCalendar =
        calendarFor?.invoke(qktSymbol) ?: calendar

    internal fun bookAnnualization(compiled: PortfolioCompiled): java.math.BigDecimal {
        val stream = compiled.ast.streams.firstOrNull() ?: return java.math.BigDecimal("252")
        val window = TimeWindow.parse(stream.timeframe)
        return calendarForSymbol(stream.qktSymbol).tradingPeriodsPerYear(window)
    }

    private companion object {
        const val PORTFOLIO_RISK_PREFIX = "__portfolio__"
    }
}
