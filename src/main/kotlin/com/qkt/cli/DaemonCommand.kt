package com.qkt.cli

import com.qkt.cli.daemon.AutoDeployRetrier
import com.qkt.cli.daemon.CommandChannel
import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.ControlPlane
import com.qkt.cli.daemon.ControlToken
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
     * [CompositeMarketSource] from the configured trading accounts' own feeds, with
     * TradingView as fallback. Tests pass an explicit
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
        val controlToken =
            try {
                ControlToken.forDaemon(stateDir)
            } catch (e: Exception) {
                System.err.println("qkt: control token initialization failed: ${e.message}")
                return ExitCodes.USER_ERROR
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
        // Forward reference so the connector context can ask which deployed strategies trade an
        // account. Recovery runs strictly after the broker is built, so by the time a broker asks,
        // `registryRef.get()` is populated. See #154.
        val registryRef = AtomicReference<StrategyRegistry?>(null)
        val accounts =
            try {
                cfg.openAccounts(stateDir.stateRoot) { accountName ->
                    registryRef
                        .get()
                        ?.list()
                        .orEmpty()
                        .filter { handle ->
                            handle.live
                                .streamBrokers()
                                .values
                                .any { it.equals(accountName, ignoreCase = true) }
                        }.map { it.name }
                }
            } catch (e: Exception) {
                System.err.println("qkt: broker account load failed: ${e.message}")
                runCatching { insightsSink?.close() }
                return ExitCodes.USER_ERROR
            }
        val verifiedAccounts =
            try {
                accounts.verifyAll()
            } catch (e: Exception) {
                System.err.println("qkt: broker account preflight failed: ${e.message}")
                runCatching { accounts.close() }
                runCatching { insightsSink?.close() }
                return ExitCodes.USER_ERROR
            }
        val daemonCalendarFor: (String) -> com.qkt.common.TradingCalendar = { qktSymbol ->
            liveCalendarFor(qktSymbol, accounts)
        }
        val daemonInstrumentRegistry =
            try {
                com.qkt.instrument.LayeredInstrumentRegistry(
                    buildList {
                        val configured = Path.of(cfg.dataRoot).resolve("instruments.yaml")
                        if (Files.isRegularFile(configured)) {
                            add(
                                com.qkt.instrument.YamlInstrumentRegistry
                                    .load(configured),
                            )
                        }
                        add(com.qkt.instrument.StandardInstrumentRegistry)
                    },
                )
            } catch (e: Exception) {
                System.err.println("qkt: instrument registry load failed: ${e.message}")
                runCatching { insightsSink?.close() }
                return ExitCodes.USER_ERROR
            }
        val liveAccounts = verifiedAccounts.filter { it.second.type == com.qkt.connectivity.AccountType.LIVE }
        if (!cfg.runtimeMode.production && liveAccounts.isNotEmpty()) {
            System.err.println(
                "[WARN] live-money account(s) ${liveAccounts.joinToString { it.first.config.name }} detected while " +
                    "runtime.mode=${cfg.runtimeMode.name.lowercase()}; production governance checks are not active",
            )
        }
        val journalRetention =
            com.qkt.observe
                .JournalRetention(
                    roots =
                        listOf(
                            stateDir.stateRoot.resolve("audit-journal"),
                            stateDir.stateRoot.resolve("mt5-transport-journal"),
                        ),
                    retentionDays = cfg.journalRetentionDays,
                    clock = com.qkt.common.SystemClock(),
                    compressAfterDays = cfg.journalCompressAfterDays,
                ).also { it.start() }
        val diskSpaceGuard =
            com.qkt.observe
                .DiskSpaceGuard(
                    root = stateDir.stateRoot,
                    floorBytes = cfg.diskFreeAlertGb.toLong() * 1024L * 1024L * 1024L,
                    onLow = { free, floor ->
                        if (NotifyEventKind.DISK_SPACE_LOW in notifyEventKinds) {
                            notifier.notify(
                                NotificationEvent.DiskSpaceLow(
                                    path = stateDir.stateRoot.toString(),
                                    freeBytes = free,
                                    floorBytes = floor,
                                    timestamp = Instant.now().toEpochMilli(),
                                ),
                            )
                        }
                    },
                ).also { it.start() }
        val insightsSharedDeals =
            com.qkt.observe.insights
                .SharedDealFetch()
        val brokerFactories: Map<String, com.qkt.broker.BrokerFactory> = accounts.orderEntry()

        val effectiveSourceFactory: (List<String>) -> MarketSource =
            sourceFactory
                ?: MarketSourceFactory.composite(accounts.marketDataRoutes(), source = cfg.source, hub = cfg.hub)

        val statePersistor =
            statePersistorFactory?.invoke(cfg, stateDir.stateRoot)
                ?: cfg.statePersistor(stateDir.stateRoot)
        val registry =
            StrategyRegistry(
                StrategyHandle.RealFactory(
                    stateDir = stateDir,
                    marketSourceProvider = effectiveSourceFactory,
                    brokerFactories = brokerFactories,
                    instrumentRegistry = daemonInstrumentRegistry,
                    calendarFor = daemonCalendarFor,
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
                    runawayMaxRoundTrips = cfg.runawayMaxRoundTrips,
                    runawayMaxRejections = cfg.runawayMaxRejections,
                    candleCloseGraceMs = cfg.candleCloseGraceMs,
                    accountingConfig = cfg.accountingConfig,
                    liveEquityBasis = cfg.liveEquityBasis,
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
                    insightsDeployedIds = {
                        registryRef
                            .get()
                            ?.list()
                            ?.map { it.ast.name }
                            .orEmpty()
                    },
                    insightsStatePollMs = cfg.insights.statePollMs,
                    insightsSharedDeals = insightsSharedDeals,
                    insightsDealBackfillDays = cfg.insights.dealBackfillDays,
                    marketDataGateConfig = cfg.marketData,
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

        // `book_risk` is enforced by the portfolio deployer alone: a standalone strategy is
        // deployed through StrategyHandle, which never builds a BookRiskController. A config that
        // declares gross- or net-exposure limits and then deploys single strategies therefore
        // reads as protected while nothing bounds total notional — measured on a demo account, a
        // 100-leg burst reached roughly 1.16x capital against a declared 0.60. Say so once, at
        // start, rather than let the silence imply a limit that is not there (catalog row A23).
        if (cfg.bookRisk?.limits != null) {
            org.slf4j.LoggerFactory
                .getLogger("com.qkt.cli.DaemonCommand")
                .warn(STANDALONE_BOOK_RISK_WARNING)
        }

        val portfolioDeployer =
            com.qkt.cli.daemon.portfolio
                .PortfolioDeployer(
                    stateDir = stateDir,
                    marketSourceProvider = effectiveSourceFactory,
                    brokerFactories = brokerFactories,
                    instrumentRegistry = daemonInstrumentRegistry,
                    calendarFor = daemonCalendarFor,
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                    dailyDdBasis = cfg.dailyDdBasis,
                    bookRiskConfig = cfg.bookRisk,
                    perStrategyRisk = cfg.perStrategyRisk,
                    accountingConfig = cfg.accountingConfig,
                    liveEquityBasis = cfg.liveEquityBasis,
                    maxOrderQty = cfg.maxOrderQty,
                    maxOrderNotional = cfg.maxOrderNotional,
                    priceCollarFrac = cfg.priceCollarFrac,
                    runawayMaxRoundTrips = cfg.runawayMaxRoundTrips,
                    runawayMaxRejections = cfg.runawayMaxRejections,
                    candleCloseGraceMs = cfg.candleCloseGraceMs,
                    marginFloorPct = cfg.marginFloorPct,
                    measuredUsageHours = cfg.measuredUsageHours,
                    measuredUsageMaxQty = cfg.measuredUsageMaxQty,
                    persistor = statePersistor,
                    journalRoot = stateDir.stateRoot.resolve("journal"),
                    auditJournalRoot = stateDir.stateRoot.resolve("audit-journal"),
                    notifier = notifier,
                    notifyEvents = notifyEventKinds,
                    insightsSink = insightsSink,
                    insightsEvents = cfg.insights.events,
                    insightsDeployedIds = {
                        registryRef
                            .get()
                            ?.list()
                            ?.map { it.ast.name }
                            .orEmpty()
                    },
                    insightsStatePollMs = cfg.insights.statePollMs,
                    insightsSharedDeals = insightsSharedDeals,
                    insightsDealBackfillDays = cfg.insights.dealBackfillDays,
                    marketDataGateConfig = cfg.marketData,
                )
        val autoDeployRetrier =
            AutoDeployRetrier(
                deploy = { name, file -> deployLoadDirFile(name, file, registry, portfolioDeployer) },
                alreadyDeployed = { name -> registry.get(name) != null || registry.getPortfolio(name) != null },
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
                controlToken = controlToken.value,
                pendingAutoDeploys = { autoDeployRetrier.pending() },
            )
        plane.start()
        instanceLock.writeControlPort(plane.boundPort)
        commandChannels.forEach { it.start() }

        println("[INFO] qkt ${BuildInfo.VERSION} daemon starting")
        println("[INFO] state directory: ${stateDir.root}")
        println("[INFO] strategy state: ${stateDir.stateRoot}")
        println(
            "[INFO] control plane: http://127.0.0.1:${plane.boundPort} " +
                "auth=ENABLED token from ${controlToken.source} " +
                "(state file: ${stateDir.controlPortFile})",
        )

        if (verifiedAccounts.isNotEmpty()) {
            println("[INFO] broker accounts loaded: ${accounts.accounts.joinToString { it.config.name }}")
            verifiedAccounts.forEach { (_, profile) -> println("[INFO] account: ${profile.description}") }
        }

        val failedAutoDeploys =
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
        for (failure in failedAutoDeploys) autoDeployRetrier.schedule(failure.name, failure.file, failure.message)
        if (failedAutoDeploys.isNotEmpty()) {
            println(
                "[INFO] ${failedAutoDeploys.size} auto-deploy(s) pending retry; /health reports degraded until they land",
            )
            autoDeployRetrier.start()
        }

        println("[INFO] daemon ready")

        if (NotifyEventKind.DAEMON_STARTED in notifyEventKinds) {
            notifier.notify(
                NotificationEvent.DaemonStarted(
                    version = BuildInfo.VERSION,
                    strategies = registry.list().map { it.name },
                    timestamp = startedAt.toEpochMilli(),
                    accounts = verifiedAccounts.map { (_, profile) -> profile.description },
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
            runCatching { autoDeployRetrier.close() }
            runCatching { plane.close() }
            commandChannels.forEach { runCatching { it.close() } }
            runCatching { dailySummarySchedulers.forEach { it.close() } }
            runCatching { registry.stopAll() }
            runCatching { statePersistor.close() }
            runCatching { journalRetention.close() }
            runCatching { diskSpaceGuard.close() }
            runCatching { accounts.close() }
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

    /** One `--load-dir` file that failed to deploy at startup and is owed a retry (#1055). */
    internal data class FailedAutoDeploy(
        val name: String,
        val file: java.nio.file.Path,
        val message: String,
    )

    /** Deploy one `--load-dir` file as a strategy or portfolio; throws with the deploy error. */
    internal fun deployLoadDirFile(
        name: String,
        file: java.nio.file.Path,
        registry: StrategyRegistry,
        portfolioDeployer: PortfolioDeployer?,
    ): String =
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

    /**
     * Auto-deploy every `.qkt` in [dir]. Returns the files that failed so the caller can
     * hand them to an [AutoDeployRetrier] — a transient failure at boot (venue gap, gateway
     * reconnecting) must not leave the daemon idle (#1055).
     */
    internal fun loadDirIfRequested(
        dir: String?,
        registry: StrategyRegistry,
        portfolioDeployer: PortfolioDeployer? = null,
        onDeployError: (name: String, message: String) -> Unit = { _, _ -> },
    ): List<FailedAutoDeploy> {
        if (dir == null) return emptyList()
        val path =
            java.nio.file.Path
                .of(dir)
        if (!java.nio.file.Files
                .isDirectory(path)
        ) {
            System.err.println("[WARN] --load-dir $dir is not a directory; skipping")
            return emptyList()
        }
        val failed = ArrayList<FailedAutoDeploy>()
        java.nio.file.Files.list(path).use { stream ->
            for (file in stream.toList()) {
                if (!file.toString().endsWith(".qkt")) continue
                val name = file.fileName.toString().removeSuffix(".qkt")
                runCatching { deployLoadDirFile(name, file, registry, portfolioDeployer) }
                    .onSuccess { println("[INFO] auto-deployed $name from $file") }
                    .onFailure { e ->
                        val message = e.message ?: e::class.java.simpleName
                        System.err.println("[WARN] failed to auto-deploy $name: $message")
                        failed += FailedAutoDeploy(name, file, message)
                        onDeployError(name, message)
                    }
            }
        }
        return failed
    }

    private fun controlClient(stateDir: StateDir): ControlClient = ControlClient(stateDir)

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun defaultTradingViewSource(symbols: List<String>): MarketSource = TradingViewMarketSource.connect()
    }
}

/**
 * The trading calendar for [qktSymbol]: its account's trading hours when an account serves the
 * prefix, otherwise the backtest defaults (e.g. `PAPER:SPX` resolves to NYSE hours).
 */
internal fun liveCalendarFor(
    qktSymbol: String,
    accounts: com.qkt.connectivity.AccountDirectory,
): com.qkt.common.TradingCalendar =
    accounts.tradingHoursFor(qktSymbol)
        ?: BacktestContext.defaultCalendars().calendarFor(qktSymbol.substringAfter(':'))
