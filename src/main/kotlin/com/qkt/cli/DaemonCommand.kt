package com.qkt.cli

import com.qkt.broker.mt5.MT5AccountVerifier
import com.qkt.broker.mt5.MT5TradeMode
import com.qkt.cli.daemon.CommandChannel
import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.ControlPlane
import com.qkt.cli.daemon.DaemonInstanceLock
import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.RegistryDaemonControl
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.cli.daemon.TelegramCommandChannel
import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.notify.ChannelConfig
import com.qkt.notify.ChannelRegistry
import com.qkt.notify.CompositeNotifier
import com.qkt.notify.DailySummaryScheduler
import com.qkt.notify.FilteringNotifier
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.notify.aggregateDailySummary
import com.qkt.persistence.StatePersistor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `qkt daemon` — long-lived process hosting many strategies via the control plane.
 *
 * Subcommands: `daemon start`, `daemon stop`, `daemon status`. Auto-deploys every
 * `.qkt` file in `--load-dir` at startup; new deployments arrive through the
 * HTTP-on-127.0.0.1 control plane (`qkt deploy`, `qkt list`, etc).
 */
class DaemonCommand(
    private val args: Args,
    /**
     * Test seam. When `null` (production default), the daemon builds a
     * [CompositeMarketSource] from the loaded MT5 broker profiles plus Bybit public
     * spot/linear sources, with TradingView as fallback. Tests pass an explicit
     * factory to swap in a stub.
     */
    private val sourceFactory: ((List<String>) -> MarketSource)? = null,
    /** Test seam for verifying ownership of the daemon-wide persistence resource. */
    private val statePersistorFactory: ((Config, Path) -> StatePersistor)? = null,
) {
    fun run(): Int {
        // Sub-subcommand dispatch (implemented further in Task 9): `qkt daemon stop|status`.
        // The sub-subcommand, if present, is always argv[1] before any flags.
        return when (val sub = args.firstNonOption()) {
            null, "start" -> startDaemon()
            "stop" -> stopDaemon()
            "status" -> statusDaemon()
            else -> {
                System.err.println("qkt: unknown daemon subcommand '$sub' (expected: start, stop, status)")
                ExitCodes.ARG_ERROR
            }
        }
    }

    private fun startDaemon(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val instanceLock = stateDir.acquireDaemonLock()
        if (instanceLock == null) {
            val owner =
                runCatching {
                    Files
                        .readString(stateDir.pidFile)
                        .trim()
                }.getOrNull()
            System.err.println(
                "qkt: daemon already running for state directory ${stateDir.root}" +
                    owner?.takeIf { it.isNotEmpty() }?.let { " (pid $it)" }.orEmpty(),
            )
            return ExitCodes.USER_ERROR
        }
        return instanceLock.use { startDaemonLocked(stateDir, it) }
    }

    private fun startDaemonLocked(
        stateDir: StateDir,
        instanceLock: DaemonInstanceLock,
    ): Int {
        val configPathEarly = Config.resolvePath(args.option("config"))
        val cfg = Config.load(configPathEarly)
        if (cfg.runtimeMode.production) {
            val preflight =
                ProductionPreflight.evaluate(
                    configPath = configPathEarly,
                    stateDir = stateDir,
                )
            val failures = preflight.filter { it.status == PreflightStatus.FAIL }
            if (failures.isNotEmpty()) {
                failures.forEach { System.err.println("FAIL ${it.name}: ${it.detail}") }
                return ExitCodes.USER_ERROR
            }
        }
        val channelRegistry = ChannelRegistry.DEFAULT
        val channelNotifiers: List<Pair<ChannelConfig, Notifier>> =
            cfg.notify.enabledChannels().mapNotNull { ch ->
                val provider = channelRegistry.get(ch.type)
                if (provider == null) {
                    println("[WARN] unknown notify channel type: ${ch.type}")
                    null
                } else {
                    ch to provider.notifier(ch)
                }
            }
        val notifyEventKinds = cfg.notify.enabledEventKinds()
        val notifier: Notifier =
            CompositeNotifier(channelNotifiers.map { (ch, n) -> FilteringNotifier(ch.events, n) })
        // One insights sink per daemon, shared by every session (mirrors the notifier).
        // Disabled config (the default) constructs nothing: no queue, no thread.
        val insightsSink: com.qkt.observe.insights.InsightsSink? =
            if (cfg.insights.enabled && cfg.insights.url.isNotBlank()) {
                val insightsInstanceId = cfg.insights.instanceId.ifBlank { "qkt" }
                val insightsJournalDir =
                    if (cfg.insights.journalEnabled) {
                        cfg.insights.journalDir
                            .takeIf { it.isNotBlank() }
                            ?.let {
                                java.nio.file.Path
                                    .of(it)
                            }
                            ?: stateDir.stateRoot.resolve("insights-journal")
                    } else {
                        null
                    }
                com.qkt.observe.insights.InsightsSink(
                    url = cfg.insights.url,
                    token = cfg.insights.token,
                    instanceId = insightsInstanceId,
                    batchSize = cfg.insights.batchSize,
                    flushIntervalMs = cfg.insights.flushIntervalMs,
                    queueCapacity = cfg.insights.queueCapacity,
                    journalDir = insightsJournalDir,
                )
            } else {
                null
            }
        if (insightsSink != null && com.qkt.observe.insights.InsightsEventFamily.LOG in cfg.insights.events) {
            com.qkt.observe.insights.InsightsLogAppender
                .attach(insightsSink)
        }
        val mt5Profiles =
            try {
                com.qkt.broker.mt5
                    .MT5BrokerProfileLoader()
                    .load(
                        raw = cfg.brokers,
                        defaults = com.qkt.broker.mt5.MT5DefaultProfiles.all,
                        env = System.getenv(),
                        calendars = cfg.brokerCalendars,
                        aliases = cfg.brokerAliases,
                        capabilityRestrictions = cfg.brokerCapabilityRestrictions,
                        instrumentOverrides = cfg.brokerInstrumentOverrides,
                    )
            } catch (e: Exception) {
                System.err.println("qkt: MT5 profile load failed: ${e.message}")
                runCatching { insightsSink?.close() }
                return ExitCodes.USER_ERROR
            }
        val mt5Accounts =
            try {
                mt5Profiles.associateWith { profile ->
                    MT5AccountVerifier.fetchAndVerify(profile)
                }
            } catch (e: Exception) {
                System.err.println("qkt: MT5 account preflight failed: ${e.message}")
                runCatching { insightsSink?.close() }
                return ExitCodes.USER_ERROR
            }
        if (!cfg.runtimeMode.production && mt5Accounts.values.any { it.tradeMode == MT5TradeMode.REAL.wireValue }) {
            System.err.println(
                "[WARN] REAL MT5 account detected while runtime.mode=${cfg.runtimeMode.name.lowercase()}; " +
                    "production governance checks are not active",
            )
        }
        // Forward reference so the factory closure can query the registry that's
        // constructed below. Recovery runs strictly after the broker is built, so by
        // the time `siblingsLookup` fires, `registryRef.get()` is populated. See #154.
        val registryRef = AtomicReference<StrategyRegistry?>(null)
        val mt5Factories: Map<String, com.qkt.app.BrokerFactory> =
            mt5Profiles.associate { profile ->
                val profileLabel = profile.name
                val key = profileLabel.lowercase()
                key to
                    { bus, clock, priceTracker, _, strategyName ->
                        val siblingsLookup: () -> List<String> = {
                            val registry = registryRef.get()
                            if (registry == null || strategyName == null) {
                                emptyList()
                            } else {
                                registry
                                    .list()
                                    .asSequence()
                                    .filter { it.name != strategyName }
                                    .filter { handle ->
                                        handle.live
                                            .streamBrokers()
                                            .values
                                            .any { it.equals(profileLabel, ignoreCase = true) }
                                    }.map { it.name }
                                    .toList()
                            }
                        }
                        com.qkt.broker.mt5
                            .MT5Broker(
                                profile = profile,
                                bus = bus,
                                clock = clock,
                                priceTracker = priceTracker,
                                strategyName = strategyName,
                                siblingsLookup = siblingsLookup,
                            )
                    }
            }

        // Bybit: one shared client per daemon (REST + private WS), created only when
        // BYBIT_API_KEY is set so pure-MT5 deployments don't open an idle connection. Both
        // spot and linear factories share it; a broker's close() stops only its reconciler,
        // not the client, so the daemon owns the client lifecycle (closed on shutdown below).
        val bybitClient: com.qkt.broker.bybit.BybitClient? =
            if (!System.getenv("BYBIT_API_KEY").isNullOrEmpty()) {
                com.qkt.broker.bybit
                    .BybitClient()
                    .also { c ->
                        runCatching { c.connect() }
                            .onFailure { println("[WARN] Bybit connect failed at startup: ${it.message}") }
                    }
            } else {
                null
            }
        val bybitFactories: Map<String, com.qkt.app.BrokerFactory> =
            bybitClient?.let { client ->
                val spot: com.qkt.app.BrokerFactory = { bus, clock, _, _, _ ->
                    com.qkt.broker.bybit.spot
                        .BybitSpotBroker(client, bus, clock)
                }
                val linear: com.qkt.app.BrokerFactory = { bus, clock, _, positions, _ ->
                    com.qkt.broker.bybit.linear
                        .BybitLinearBroker(client, bus, clock, positions)
                }
                mapOf("bybit_spot" to spot, "bybit_linear" to linear)
            } ?: emptyMap()
        val brokerFactories: Map<String, com.qkt.app.BrokerFactory> = mt5Factories + bybitFactories

        val effectiveSourceFactory: (List<String>) -> MarketSource =
            sourceFactory ?: MarketSourceFactory.composite(mt5Profiles, source = cfg.source)

        val statePersistor =
            statePersistorFactory?.invoke(cfg, stateDir.stateRoot)
                ?: cfg.statePersistor(stateDir.stateRoot)
        val registry =
            StrategyRegistry(
                StrategyHandle.RealFactory(
                    stateDir = stateDir,
                    marketSourceProvider = effectiveSourceFactory,
                    brokerFactories = brokerFactories,
                    maxDailyLoss = cfg.maxDailyLoss,
                    perStrategyRisk = cfg.perStrategyRisk,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                    dailyDdBasis = cfg.dailyDdBasis,
                    startingBalance = cfg.startingBalance,
                    maxOrderQty = cfg.maxOrderQty,
                    maxOrderNotional = cfg.maxOrderNotional,
                    priceCollarFrac = cfg.priceCollarFrac,
                    accountingConfig = cfg.accountingConfig,
                    marginFloorPct = cfg.marginFloorPct,
                    measuredUsageHours = cfg.measuredUsageHours,
                    measuredUsageMaxQty = cfg.measuredUsageMaxQty,
                    journalRoot = stateDir.stateRoot.resolve("journal"),
                    auditJournalRoot = stateDir.stateRoot.resolve("audit-journal"),
                    persistor = statePersistor,
                    notifier = notifier,
                    notifyEvents = notifyEventKinds,
                    insightsSink = insightsSink,
                    insightsEvents = cfg.insights.events,
                    insightsStatePollMs = cfg.insights.statePollMs,
                    insightsDealBackfillDays = cfg.insights.dealBackfillDays,
                ),
            )
        registryRef.set(registry)
        val daemonControl =
            RegistryDaemonControl(
                registry,
                OperatorJournal(stateDir, "command-channel"),
            )
        val commandChannels: List<CommandChannel> =
            cfg.notify
                .enabledChannels()
                .filter { it.commands && it.type == "telegram" }
                .mapNotNull { TelegramCommandChannel.from(it, daemonControl) }
        val startedAt = Instant.now()
        val stopLatch = CountDownLatch(1)

        val portfolioDeployer =
            com.qkt.cli.daemon.portfolio
                .PortfolioDeployer(
                    stateDir = stateDir,
                    marketSourceProvider = effectiveSourceFactory,
                    brokerFactories = brokerFactories,
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                    dailyDdBasis = cfg.dailyDdBasis,
                    bookRiskConfig = cfg.bookRisk,
                    perStrategyRisk = cfg.perStrategyRisk,
                    accountingConfig = cfg.accountingConfig,
                    maxOrderQty = cfg.maxOrderQty,
                    maxOrderNotional = cfg.maxOrderNotional,
                    priceCollarFrac = cfg.priceCollarFrac,
                    marginFloorPct = cfg.marginFloorPct,
                    measuredUsageHours = cfg.measuredUsageHours,
                    measuredUsageMaxQty = cfg.measuredUsageMaxQty,
                    persistor = statePersistor,
                    notifier = notifier,
                    notifyEvents = notifyEventKinds,
                    insightsSink = insightsSink,
                    insightsEvents = cfg.insights.events,
                    insightsStatePollMs = cfg.insights.statePollMs,
                    insightsDealBackfillDays = cfg.insights.dealBackfillDays,
                )
        val plane =
            ControlPlane(
                registry = registry,
                bind = "127.0.0.1",
                port = args.option("control-port")?.toIntOrNull() ?: 0,
                startedAt = startedAt,
                shutdownHook = { stopLatch.countDown() },
                stateDir = stateDir,
                portfolioDeployer = portfolioDeployer,
                notifierMetrics = notifier.metrics,
                promotionGates = cfg.promotionGateConfig,
            )
        plane.start()
        instanceLock.writeControlPort(plane.boundPort)
        commandChannels.forEach { it.start() }

        println("[INFO] qkt ${BuildInfo.VERSION} daemon starting")
        println("[INFO] state directory: ${stateDir.root}")
        println("[INFO] strategy state: ${stateDir.stateRoot}")
        println(
            "[INFO] control plane: http://127.0.0.1:${plane.boundPort} " +
                "(state file: ${stateDir.controlPortFile})",
        )

        if (mt5Profiles.isNotEmpty()) {
            println("[INFO] mt5 broker profiles loaded: ${mt5Profiles.joinToString { it.name }}")
            mt5Accounts.forEach { (profile, account) ->
                println("[INFO] mt5 account: ${MT5AccountVerifier.describe(profile, account)}")
            }
        }

        loadDirIfRequested(args.option("load-dir"), registry, portfolioDeployer) { name, message ->
            if (NotifyEventKind.STRATEGY_ERROR in notifyEventKinds) {
                notifier.notify(
                    NotificationEvent.StrategyError(
                        strategyId = name,
                        message = message,
                        timestamp = Instant.now().toEpochMilli(),
                    ),
                )
            }
        }

        println("[INFO] daemon ready")

        if (NotifyEventKind.DAEMON_STARTED in notifyEventKinds) {
            notifier.notify(
                NotificationEvent.DaemonStarted(
                    version = BuildInfo.VERSION,
                    strategies = registry.list().map { it.name },
                    timestamp = startedAt.toEpochMilli(),
                    accounts =
                        mt5Accounts.map { (profile, account) ->
                            MT5AccountVerifier.describe(profile, account)
                        },
                ),
            )
        }

        // One daily-summary scheduler per channel that opts in, each pointed at that channel's own
        // notifier so the summary reaches only the channels that asked for it. The producer
        // aggregates every live session's rows into one message.
        val dailySummaryProducer = {
            aggregateDailySummary(
                rowsPerSession = registry.list().map { it.live.dailySummaryRows() },
                nowMs = Instant.now().toEpochMilli(),
            )
        }
        val dailySummarySchedulers: List<DailySummaryScheduler> =
            channelNotifiers.mapNotNull { (ch, channelNotifier) ->
                ch.dailySummaryUtc
                    .takeIf { it.isNotBlank() }
                    ?.let { utc ->
                        DailySummaryScheduler(notifier = channelNotifier, producer = dailySummaryProducer)
                            .also { it.startAtUtc(utc) }
                    }
            }

        val cleanupStarted = AtomicBoolean(false)

        fun cleanup() {
            if (!cleanupStarted.compareAndSet(false, true)) return
            runCatching { stateDir.deleteControlPort() }
            runCatching { plane.close() }
            commandChannels.forEach { runCatching { it.close() } }
            runCatching { dailySummarySchedulers.forEach { it.close() } }
            runCatching { registry.stopAll() }
            runCatching { statePersistor.close() }
            runCatching { bybitClient?.close() }
            runCatching { notifier.close() }
            runCatching { insightsSink?.close() }
        }

        val shutdown =
            Thread {
                try {
                    println("[INFO] stopping daemon")
                    val n = registry.list().size
                    if (n > 0) println("[INFO] gracefully stopping $n strateg${if (n == 1) "y" else "ies"}")
                    cleanup()
                    println("[INFO] daemon stopped")
                } finally {
                    stopLatch.countDown()
                }
            }
        Runtime.getRuntime().addShutdownHook(shutdown)

        return try {
            stopLatch.await()
            // If the latch was tripped programmatically (POST /shutdown), do the cleanup ourselves.
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            cleanup()
            ExitCodes.SUCCESS
        } catch (_: InterruptedException) {
            cleanup()
            Thread.currentThread().interrupt()
            ExitCodes.SUCCESS
        }
    }

    private fun stopDaemon(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = controlClient(stateDir)
        return try {
            client.shutdown()
            println("[INFO] daemon stop accepted")
            ExitCodes.SUCCESS
        } catch (e: ControlClient.NoDaemonRunningException) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.USER_ERROR
        } catch (e: ControlClient.DaemonError) {
            System.err.println("qkt: error: shutdown failed (${e.code}): ${e.body}")
            ExitCodes.USER_ERROR
        }
    }

    private fun statusDaemon(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = controlClient(stateDir)
        return try {
            val body = client.health()
            val port = stateDir.readControlPort() ?: 0
            if (args.flag("json")) {
                println(body)
            } else {
                println("control port: $port")
                println("state file: ${stateDir.controlPortFile}")
                println(body)
            }
            ExitCodes.SUCCESS
        } catch (e: ControlClient.NoDaemonRunningException) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.USER_ERROR
        } catch (e: ControlClient.DaemonError) {
            System.err.println("qkt: error: status failed (${e.code}): ${e.body}")
            ExitCodes.USER_ERROR
        }
    }

    internal fun loadDirIfRequested(
        dir: String?,
        registry: StrategyRegistry,
        portfolioDeployer: PortfolioDeployer? = null,
        onDeployError: (name: String, message: String) -> Unit = { _, _ -> },
    ) {
        if (dir == null) return
        val path =
            java.nio.file.Path
                .of(dir)
        if (!java.nio.file.Files
                .isDirectory(path)
        ) {
            System.err.println("[WARN] --load-dir $dir is not a directory; skipping")
            return
        }
        java.nio.file.Files.list(path).use { stream ->
            for (file in stream.toList()) {
                if (!file.toString().endsWith(".qkt")) continue
                val name = file.fileName.toString().removeSuffix(".qkt")
                runCatching {
                    when (val parsed = Dsl.parseFileAny(file)) {
                        is ParseResult.Success ->
                            when (parsed.value) {
                                is ParsedFile.StrategyFile -> {
                                    registry.deploy(name, file)
                                    "strategy"
                                }
                                is ParsedFile.PortfolioFile -> {
                                    check(portfolioDeployer != null) {
                                        "portfolio deploy not configured on this daemon"
                                    }
                                    val compiled = PortfolioLoader.load(file)
                                    val record = portfolioDeployer.deploy(name, compiled)
                                    registry.registerPortfolio(record)
                                    "portfolio"
                                }
                            }
                        is ParseResult.Failure -> {
                            val msg = parsed.errors.joinToString("\n") { "${it.line}:${it.col} ? ${it.message}" }
                            error("parse failure for $file:\n$msg")
                        }
                    }
                }.onSuccess { println("[INFO] auto-deployed $name from $file") }
                    .onFailure { e ->
                        System.err.println("[WARN] failed to auto-deploy $name: ${e.message}")
                        onDeployError(name, e.message ?: e::class.java.simpleName)
                    }
            }
        }
    }

    private fun controlClient(stateDir: StateDir): ControlClient = ControlClient(stateDir)

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun defaultTradingViewSource(symbols: List<String>): MarketSource = TradingViewMarketSource.connect()
    }
}
