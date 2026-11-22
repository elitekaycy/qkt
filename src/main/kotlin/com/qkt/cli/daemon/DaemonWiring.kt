package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.notify.NotificationEvent
import com.qkt.notify.aggregateDailySummary
import com.qkt.observe.insights.InsightsConfig
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** The deployed strategies whose streams route to [accountName] (any case), as the broker context asks for them. */
internal fun strategiesOnAccount(
    registry: StrategyRegistry?,
    accountName: String,
): List<String> =
    registry
        ?.list()
        .orEmpty()
        .filter { handle ->
            handle.live
                .streamBrokers()
                .values
                .any { it.equals(accountName, ignoreCase = true) }
        }.map { it.name }

/** [strategiesOnAccount] over whatever registry [registryRef] holds when the broker context asks. */
internal fun strategiesOnAccount(registryRef: AtomicReference<StrategyRegistry?>): (String) -> List<String> =
    { accountName -> strategiesOnAccount(registryRef.get(), accountName) }

/** The `STRATEGY` names currently deployed, for the insights state poller; empty before the registry exists. */
internal fun deployedStrategyNames(registry: StrategyRegistry?): List<String> =
    registry
        ?.list()
        ?.map { it.ast.name }
        .orEmpty()

/** Where the insights journal spools: the configured directory, else under the daemon state root; null when off. */
internal fun insightsJournalDir(
    insights: InsightsConfig,
    stateRoot: Path,
): Path? =
    if (insights.journalEnabled) {
        insights.journalDir
            .takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?: stateRoot.resolve("insights-journal")
    } else {
        null
    }

/** How the auto-deploy retrier redeploys a `--load-dir` file: the same path startup used. */
internal fun loadDirDeployer(
    registry: StrategyRegistry,
    portfolioDeployer: PortfolioDeployer?,
): (String, Path) -> Unit = { name, file -> deployLoadDirFile(name, file, registry, portfolioDeployer) }

/** One daily summary over every live session of [registry], as of [nowMs]. */
internal fun dailySummaryOf(
    registry: StrategyRegistry,
    nowMs: Long = Instant.now().toEpochMilli(),
): NotificationEvent.DailySummary =
    aggregateDailySummary(
        rowsPerSession = registry.list().map { it.live.dailySummaryRows() },
        nowMs = nowMs,
    )
