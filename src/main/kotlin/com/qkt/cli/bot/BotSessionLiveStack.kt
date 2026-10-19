package com.qkt.cli.bot

import com.qkt.cli.Config
import com.qkt.cli.InstrumentFiles
import com.qkt.cli.configNotifier
import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.liveCalendarFor
import com.qkt.cli.liveFactory
import com.qkt.cli.openAccounts
import com.qkt.connectivity.AccountDirectory
import java.nio.file.Path

/**
 * What a live bot session trades with, built and checked the way `qkt daemon` builds and checks its own:
 * the production preflight when `runtime.mode` is production, the instrument files, every account opened
 * and verified against the identity the config expects, the live-money warning, calendars, notifier,
 * insights sink, state persistor and the deploy factory over all of it.
 */
internal class LiveSessionStack(
    val cfg: Config,
    configPath: Path?,
    val stateDir: StateDir,
) : AutoCloseable {
    val instruments = InstrumentFiles.registry(Path.of(cfg.dataRoot), null, liveClock = com.qkt.common.SystemClock())
    val accounts: AccountDirectory
    val insightsSink = sessionInsightsSink(cfg, stateDir.stateRoot)
    val factory: StrategyHandle.RealFactory

    init {
        if (cfg.runtimeMode.production && configPath != null) {
            val failures =
                com.qkt.cli.ProductionPreflight
                    .evaluate(configPath = configPath, stateDir = stateDir)
                    .filter { it.status == com.qkt.cli.PreflightStatus.FAIL }
            require(
                failures.isEmpty(),
            ) { "production preflight failed: ${failures.joinToString { "${it.name}: ${it.detail}" }}" }
        }
        accounts = cfg.openAccounts(stateDir.stateRoot, instruments)
        try {
            require(accounts.accounts.isNotEmpty()) { "no broker accounts in config — a live session needs one" }
            val verified =
                com.qkt.connectivity.AccountPreflight
                    .fromEnv()
                    .verifyAll(accounts)
            val live = verified.filter { it.second.type == com.qkt.connectivity.AccountType.LIVE }
            if (!cfg.runtimeMode.production && live.isNotEmpty()) {
                System.err.println(
                    "[WARN] live-money account(s) ${live.joinToString { it.first.config.name }} detected while " +
                        "runtime.mode=${cfg.runtimeMode.name.lowercase()}; production governance checks are not active",
                )
            }
            val (_, notifier, notifyEvents) = configNotifier(cfg)
            factory =
                liveFactory(
                    cfg = cfg,
                    stateDir = stateDir,
                    marketSourceProvider =
                        com.qkt.cli.MarketSourceFactory.composite(
                            accounts.marketDataRoutes(),
                            cfg.source,
                            cfg.hub,
                            instruments,
                        ),
                    brokerFactories = accounts.orderEntry(),
                    instrumentRegistry = instruments,
                    calendarFor = { liveCalendarFor(it, accounts) },
                    persistor = cfg.statePersistor(stateDir.stateRoot),
                    notifier = notifier,
                    notifyEvents = notifyEvents,
                    insightsSink = insightsSink,
                    insightsDeployedIds = { emptyList() },
                    insightsSharedDeals =
                        com.qkt.observe.insights
                            .SharedDealFetch(),
                )
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun close() {
        runCatching { accounts.close() }
    }
}

/** Strategy names a daemon on [stateDir] is running, or none when no daemon runs there. */
internal fun daemonStrategyNames(stateDir: StateDir): List<String> =
    try {
        Regex("\"name\":\"([^\"]+)\"").findAll(ControlClient(stateDir).list()).map { it.groupValues[1] }.toList()
    } catch (_: ControlClient.NoDaemonRunningException) {
        emptyList()
    }

/** Refuses a session identity a daemon already deploys: the two would share one state directory. */
internal fun refuseDeployedIdentities(
    identities: List<String>,
    deployed: List<String>,
) {
    val names = deployed.flatMap { listOf(it, it.substringAfterLast('/')) }.toSet()
    val clash = identities.filter { it in names }
    require(clash.isEmpty()) {
        "identity ${clash.joinToString()} is a strategy the daemon on this state directory runs; " +
            "pick another --identities name or another --state-dir"
    }
}

/** One insights sink for the session and its trail, so a journal directory has one writer; null when off. */
internal fun sessionInsightsSink(
    cfg: Config,
    stateRoot: Path,
): com.qkt.observe.insights.InsightsSink? {
    if (!cfg.insights.enabled || cfg.insights.url.isBlank()) return null
    val journal =
        if (cfg.insights.journalEnabled) {
            Path.of(cfg.insights.journalDir.ifBlank { stateRoot.resolve("bot-insights-journal").toString() })
        } else {
            null
        }
    return com.qkt.observe.insights.InsightsSink(
        url = cfg.insights.url,
        token = cfg.insights.token,
        instanceId = cfg.insights.instanceId.ifBlank { "qkt" },
        batchSize = cfg.insights.batchSize,
        flushIntervalMs = cfg.insights.flushIntervalMs,
        queueCapacity = cfg.insights.queueCapacity,
        journalDir = journal,
    )
}
