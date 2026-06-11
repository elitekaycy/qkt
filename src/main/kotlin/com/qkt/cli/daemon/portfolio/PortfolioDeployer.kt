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
import com.qkt.dsl.portfolio.CompiledChild
import com.qkt.dsl.portfolio.PortfolioCompiled
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
    private val clock: com.qkt.common.Clock = com.qkt.common.SystemClock(),
    private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
    /** Telegram alert sink shared across every portfolio child. Default discards events. */
    private val notifier: Notifier = NoopNotifier,
    private val notifyEvents: Set<NotifyEventKind> = emptySet(),
    /** Insights egress sink shared across every portfolio child; null disables. */
    private val insightsSink: com.qkt.observe.insights.InsightsSink? = null,
    private val insightsEvents: Set<com.qkt.observe.insights.InsightsEventFamily> = emptySet(),
    private val insightsSnapshotIntervalMs: Long = 5_000L,
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
            for (compiledChild in compiled.children) {
                val (handle, wrapper) =
                    createChild(portfolioName, compiledChild, allocations[compiledChild.alias])
                children.add(handle)
                childWrappers.add(wrapper)
            }
            val symbols =
                compiled.ast.streams
                    .map { it.qktSymbol }
                    .distinct()
            val supervisor =
                PortfolioSupervisor(
                    ast = compiled.ast,
                    children = childWrappers,
                    marketSource = if (symbols.isEmpty()) null else marketSourceProvider(symbols),
                    riskAggregator = buildRiskAggregator(portfolioName, compiled, childWrappers),
                    riskIntervalMs = riskIntervalMs,
                )
            supervisor.start()

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
    ): PortfolioRiskAggregator? {
        val capital = compiled.ast.capital
        if (capital == null || (maxDrawdownPct == null && maxDailyDrawdownPct == null)) return null

        val pnlSources: List<() -> com.qkt.app.SessionPnl> =
            compiled.children.zip(wrappers).map { (child, w) ->
                { w.handle.live.pnlSnapshot(child.strategyId) }
            }
        val targets: List<ChildRiskTarget> =
            wrappers.map { w ->
                object : ChildRiskTarget {
                    override fun flatten() = w.handle.live.flatten()

                    override fun halt(reason: String) = w.handle.live.halt(reason)

                    override fun resume() = w.handle.live.resume()
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
                capital,
                dailyDdBasis,
            )
        val haltRules =
            buildList {
                maxDrawdownPct?.let {
                    add(
                        com.qkt.risk.rules
                            .MaxDrawdown(it, totalDdBasis, capital),
                    )
                }
                maxDailyDrawdownPct?.let {
                    add(
                        com.qkt.risk.rules
                            .MaxDailyDrawdown(it),
                    )
                }
            }
        return PortfolioRiskAggregator(targets, bookRiskState, haltRules, clock)
    }

    private fun createChild(
        portfolioName: String,
        compiledChild: CompiledChild,
        allocatedCapital: java.math.BigDecimal? = null,
    ): Pair<StrategyHandle, ChildHandle> {
        val childName = "$portfolioName/${compiledChild.alias}"
        val gateActive = AtomicBoolean(false)
        val operatorStop = AtomicBoolean(false)
        val effectiveActive: () -> Boolean = { gateActive.get() && !operatorStop.get() }

        val symbols =
            compiledChild.ast.streams
                .map { it.qktSymbol }
                .distinct()
        val source = marketSourceProvider(symbols)
        val ring = EventRing(capacity = ringSize)
        val startMs = System.currentTimeMillis()
        val startedAt = Instant.ofEpochMilli(startMs)

        val candleWindow: TimeWindow? =
            compiledChild.ast.streams
                .firstOrNull()
                ?.timeframe
                ?.let { TimeWindow.parse(it) }

        val haltRules: List<com.qkt.risk.HaltRule> =
            if (maxDailyLoss.signum() > 0) {
                listOf(
                    com.qkt.risk.rules
                        .MaxDailyLoss(maxDailyLoss),
                )
            } else {
                emptyList()
            }
        val session =
            LiveSession(
                strategies = listOf(compiledChild.strategyId to compiledChild.compiled),
                haltRules = haltRules,
                source = source,
                symbols = compiledChild.symbols,
                candleWindow = candleWindow,
                mdcStrategy = childName,
                onTrade = { trade, realized, _ ->
                    com.qkt.cli.daemon.logging.withMdc("strategy", childName) {
                        com.qkt.cli.daemon.logging.withMdc("parent", portfolioName) {
                            ring.append("trade", tradeToJson(trade, realized))
                            com.qkt.cli.daemon.TradeLog
                                .emit(trade, realized)
                        }
                    }
                },
                onSignal = { sig ->
                    com.qkt.cli.daemon.logging.withMdc("strategy", childName) {
                        com.qkt.cli.daemon.logging.withMdc("parent", portfolioName) {
                            ring.append("signal", signalToJson(sig))
                        }
                    }
                },
                gate = effectiveActive,
                brokerFactories = brokerFactories,
                persistor = persistor,
                notifier = notifier,
                notifyEvents = notifyEvents,
                insightsSink = insightsSink,
                insightsEvents = insightsEvents,
                insightsSnapshotIntervalMs = insightsSnapshotIntervalMs,
                startingBalances =
                    allocatedCapital?.let { mapOf(compiledChild.strategyId to it) } ?: emptyMap(),
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
                        pnl = session.pnlSnapshot(compiledChild.ast.name),
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
}
