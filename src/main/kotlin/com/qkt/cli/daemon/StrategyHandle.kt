package com.qkt.cli.daemon

import com.qkt.app.LiveSession
import com.qkt.app.LiveSessionHandle
import com.qkt.candles.TimeWindow
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.MarketSource
import com.qkt.notify.NoopNotifier
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class StrategyHandle(
    val name: String,
    val ast: StrategyAst,
    val live: LiveSessionHandle,
    val observability: ObservabilityServer,
    val ring: EventRing,
    val logFile: Path,
    val startedAt: Instant,
    val sourceFile: Path? = null,
    val childMeta: ChildMeta? = null,
    private val fillCount: AtomicLong = AtomicLong(0),
) : AutoCloseable {
    data class ChildMeta(
        val parent: String,
        val alias: String,
        val hold: Boolean,
        val gateActive: java.util.concurrent.atomic.AtomicBoolean,
        val operatorStop: java.util.concurrent.atomic.AtomicBoolean,
    )

    val port: Int get() = observability.boundPort

    /** Number of fills (trades) this strategy has produced. Counts trades only — not signals — and does not plateau. */
    val tradeCount: Int get() = fillCount.get().toInt()

    fun isRunning(): Boolean = live.running

    override fun close() {
        live.stop()
        var interrupted = false
        try {
            live.awaitTermination(Duration.ofSeconds(5))
        } catch (_: InterruptedException) {
            interrupted = true
        } finally {
            observability.close()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun interface Factory {
        fun create(
            name: String,
            file: Path,
            ignoreMismatches: Boolean,
        ): StrategyHandle
    }

    class RealFactory(
        private val stateDir: StateDir,
        private val marketSourceProvider: (List<String>) -> MarketSource,
        private val ringSize: Int = 1000,
        private val bind: String = "127.0.0.1",
        private val brokerFactories: Map<String, com.qkt.app.BrokerFactory> = emptyMap(),
        /**
         * Daemon-level daily-loss cap. When realized P&L for the day breaches the
         * negation of this value, [com.qkt.risk.rules.MaxDailyLoss] halts and the risk
         * engine rejects further submissions. Set to `BigDecimal.ZERO` to disable;
         * default comes from [com.qkt.cli.Config.maxDailyLoss] (or [com.qkt.cli.Config.DEFAULT_MAX_DAILY_LOSS]
         * when no config is loaded).
         */
        private val maxDailyLoss: java.math.BigDecimal = com.qkt.cli.Config.DEFAULT_MAX_DAILY_LOSS,
        /**
         * Phase 25D: per-strategy risk caps keyed by strategy name. When the deployed
         * strategy's name matches an entry, those caps layer on top of the daemon-global
         * rules. Default empty map means every strategy uses only the global rules.
         */
        private val perStrategyRisk: Map<String, com.qkt.cli.PerStrategyRisk> = emptyMap(),
        /**
         * Prop-firm drawdown halts (#348). [maxDrawdownPct]/[maxDailyDrawdownPct] are fractions
         * (null disables); [totalDdBasis]/[dailyDdBasis] pick how each is measured; [startingBalance]
         * is the account basis for static total DD and the daily reference.
         */
        private val maxDrawdownPct: java.math.BigDecimal? = null,
        private val maxDailyDrawdownPct: java.math.BigDecimal? = null,
        private val totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
        private val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
        private val startingBalance: java.math.BigDecimal = java.math.BigDecimal.ZERO,
        private val maxOrderQty: java.math.BigDecimal =
            com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
        private val maxOrderNotional: java.math.BigDecimal =
            com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
        private val priceCollarFrac: java.math.BigDecimal =
            com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
        private val marginFloorPct: java.math.BigDecimal = java.math.BigDecimal("200"),
        /**
         * Measured-usage window hours. Factory default 0 (off) so embedded/test
         * factories opt in; the production daemon always passes
         * [com.qkt.cli.Config.measuredUsageHours], whose default is 24 — the real
         * deploy path is ON unless the operator opts out explicitly.
         */
        private val measuredUsageHours: Long = 0L,
        private val measuredUsageMaxQty: java.math.BigDecimal =
            com.qkt.risk.rules.MeasuredUsage.DEFAULT_MEASURED_MAX_QTY,
        /** Order-event journal root; null disables journaling (tests). */
        private val journalRoot: java.nio.file.Path? = null,
        private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
        /**
         * Telegram alert sink shared across every strategy this daemon hosts. Default
         * [NoopNotifier] discards events — production daemons pass the single
         * [com.qkt.notify.TelegramNotifier] built from [com.qkt.cli.Config.notify].
         */
        private val notifier: Notifier = NoopNotifier,
        private val notifyEvents: Set<NotifyEventKind> = emptySet(),
        /** Insights egress sink shared across every strategy this daemon hosts; null disables. */
        private val insightsSink: com.qkt.observe.insights.InsightsSink? = null,
        private val insightsEvents: Set<com.qkt.observe.insights.InsightsEventFamily> = emptySet(),
        private val insightsStatePollMs: Long = 10_000L,
        private val insightsDealBackfillDays: Long = 30L,
    ) : Factory {
        override fun create(
            name: String,
            file: Path,
            ignoreMismatches: Boolean,
        ): StrategyHandle {
            val ast =
                when (val parsed = Dsl.parseFile(file)) {
                    is ParseResult.Success -> parsed.value
                    is ParseResult.Failure -> {
                        val msg = parsed.errors.joinToString("\n") { e -> "${e.line}:${e.col} — ${e.message}" }
                        error("parse failure for $file:\n$msg")
                    }
                }
            val strategy = AstCompiler().compile(ast)
            val symbols = ast.streams.map { it.qktSymbol }.distinct()
            val candleWindow: TimeWindow? =
                ast.streams
                    .firstOrNull()
                    ?.timeframe
                    ?.let { TimeWindow.parse(it) }

            val source = marketSourceProvider(symbols)
            val ring = EventRing(capacity = ringSize)
            val fillCount = AtomicLong(0)
            val startMs = System.currentTimeMillis()
            val startedAt = Instant.ofEpochMilli(startMs)

            val haltRules: List<com.qkt.risk.HaltRule> =
                com.qkt.risk.HaltRules.standard(
                    maxDailyLoss = maxDailyLoss,
                    maxDrawdownPct = maxDrawdownPct,
                    maxDailyDrawdownPct = maxDailyDrawdownPct,
                    totalDdBasis = totalDdBasis,
                    startingBalance = startingBalance,
                )
            val perStrategyOverride = perStrategyRisk[ast.name]
            val session =
                LiveSession(
                    strategies = listOf(ast.name to strategy),
                    haltRules = haltRules,
                    source = source,
                    symbols = symbols,
                    candleWindow = candleWindow,
                    // The MDC carries the DSL strategy id, not the deploy name: log
                    // attribution downstream (per-strategy files, insights) must match
                    // the id every trading event uses, or consumers see two strategies
                    // (e.g. deploy "hedge-straddle" vs STRATEGY hedge_straddle).
                    mdcStrategy = ast.name,
                    onTrade = { trade, realized, _ ->
                        com.qkt.cli.daemon.logging.withMdc("strategy", ast.name) {
                            fillCount.incrementAndGet()
                            ring.append("trade", tradeToJson(trade, realized))
                            TradeLog.emit(trade, realized)
                        }
                    },
                    onSignal = { sig ->
                        com.qkt.cli.daemon.logging.withMdc("strategy", ast.name) {
                            ring.append("signal", signalToJson(sig))
                        }
                    },
                    brokerFactories = brokerFactories,
                    persistor = persistor,
                    ignoreMismatches = ignoreMismatches,
                    notifier = notifier,
                    notifyEvents = notifyEvents,
                    perStrategyMaxDailyLoss = perStrategyOverride?.maxDailyLoss,
                    perStrategyMaxPositionSize = perStrategyOverride?.maxPositionSize,
                    perStrategyMaxOpenPositions = perStrategyOverride?.maxOpenPositions,
                    perStrategyMaxDrawdownPct = perStrategyOverride?.maxDrawdownPct,
                    perStrategyMaxDailyDrawdownPct = perStrategyOverride?.maxDailyDrawdownPct,
                    initialBalance = startingBalance,
                    totalDdBasis = totalDdBasis,
                    dailyDdBasis = dailyDdBasis,
                    maxOrderQty = maxOrderQty,
                    maxOrderNotional = maxOrderNotional,
                    priceCollarFrac = priceCollarFrac,
                    marginFloorPct = marginFloorPct,
                    measuredUsageHours = measuredUsageHours,
                    measuredUsageMaxQty = measuredUsageMaxQty,
                    journal =
                        journalRoot?.let {
                            com.qkt.observe.OrderJournal(it, com.qkt.common.SystemClock())
                        },
                    insightsSink = insightsSink,
                    insightsEvents = insightsEvents,
                    insightsStatePollMs = insightsStatePollMs,
                    insightsDealBackfillDays = insightsDealBackfillDays,
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
                            ast.name,
                            ast.version,
                            startMs,
                            startedAt.toString(),
                            session.recentTrades(),
                            layers,
                            streamBrokers = session.streamBrokers(),
                            pnl = session.pnlSnapshot(ast.name),
                            inboundQueueDepth = session.inboundQueueDepth(),
                            staleSymbols = session.staleSymbols().keys.sorted(),
                            openPositions = session.positionsFor(ast.name),
                        )
                    },
                    running = { session.running },
                    onStop = { _ -> session.stop() },
                    bind = bind,
                    port = 0,
                    latencyProvider = { session.latencySnapshot() },
                ).also { it.start() }

            // Ensure log file exists (logback's SiftingAppender lazily creates it on first write,
            // but the daemon's API contract is that the file path is valid immediately).
            // Keyed by the DSL strategy id — the sift discriminator names files from the
            // same MDC value, and the /logs route resolves through handle.logFile, so a
            // deploy alias that differs from the id still finds its file.
            val logFile = stateDir.logFile(ast.name)
            java.nio.file.Files
                .createDirectories(logFile.parent)
            if (!java.nio.file.Files
                    .exists(logFile)
            ) {
                java.nio.file.Files
                    .createFile(logFile)
            }

            return StrategyHandle(
                name = name,
                ast = ast,
                live = session,
                observability = server,
                ring = ring,
                logFile = logFile,
                startedAt = startedAt,
                sourceFile = file.toAbsolutePath().normalize(),
                fillCount = fillCount,
            )
        }
    }
}
