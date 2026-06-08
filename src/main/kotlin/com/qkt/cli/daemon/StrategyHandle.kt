package com.qkt.cli.daemon

import com.qkt.app.LiveSession
import com.qkt.app.LiveSessionHandle
import com.qkt.candles.TimeWindow
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.MarketSource
import com.qkt.notify.NoopNotifier
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import java.nio.file.Path
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
        observability.close()
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
        private val candleHub: CandleHub? = null,
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
        private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
        /**
         * Telegram alert sink shared across every strategy this daemon hosts. Default
         * [NoopNotifier] discards events — production daemons pass the single
         * [com.qkt.notify.TelegramNotifier] built from [com.qkt.cli.Config.notify].
         */
        private val notifier: Notifier = NoopNotifier,
        private val notifyEvents: Set<NotifyEventKind> = emptySet(),
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
                if (maxDailyLoss.signum() > 0) {
                    listOf(
                        com.qkt.risk.rules
                            .MaxDailyLoss(maxDailyLoss),
                    )
                } else {
                    emptyList()
                }
            val perStrategyOverride = perStrategyRisk[ast.name]
            val session =
                LiveSession(
                    strategies = listOf(ast.name to strategy),
                    haltRules = haltRules,
                    source = source,
                    symbols = symbols,
                    candleWindow = candleWindow,
                    mdcStrategy = name,
                    candleHub = candleHub,
                    onTrade = { trade, realized, _ ->
                        com.qkt.cli.daemon.logging.withMdc("strategy", name) {
                            fillCount.incrementAndGet()
                            ring.append("trade", tradeToJson(trade, realized))
                            TradeLog.emit(trade, realized)
                        }
                    },
                    onSignal = { sig ->
                        com.qkt.cli.daemon.logging.withMdc("strategy", name) {
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
            val logFile = stateDir.logFile(name)
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
                fillCount = fillCount,
            )
        }
    }
}
