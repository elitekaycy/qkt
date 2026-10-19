package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.openAccounts
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionFiles
import com.qkt.trade.session.BotSessionIdentities
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.BotSessionStreams
import com.qkt.trade.session.LiveBotRunBackend
import com.qkt.trade.venue.toQuoteContext
import java.nio.file.Path

/**
 * `session start` without `--backtest`: the configured accounts' brokers and feeds under a
 * [com.qkt.app.LiveSession], with one compiled DSL strategy per identity in the slots. When insights
 * egress is configured the session and its [com.qkt.trade.BotTrail] share one sink.
 */
internal fun startLiveSession(
    sub: Args,
    json: Boolean,
    spec: SessionStart,
): Int {
    val streams = spec.streams
    val cfg = botConfig(sub)
    val accounts = cfg.openAccounts()
    require(accounts.accounts.isNotEmpty()) { "no broker accounts in config — a live session needs one" }
    val sourceFactory =
        com.qkt.cli.MarketSourceFactory
            .composite(accounts.marketDataRoutes(), source = cfg.source)
    val history = BarHistory(capacity = maxOf(spec.historyBars, 1000))
    seedLiveWarmup(cfg, streams, spec.historyBars, history)
    val recorder = BotSessionRecorder(history)
    val identities = BotSessionIdentities.compile(streams, spec.identities)
    val haltRules =
        com.qkt.risk.HaltRules.standard(
            maxDailyLoss = cfg.maxDailyLoss,
            maxDrawdownPct = cfg.maxDrawdownPct,
            maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
            totalDdBasis = cfg.totalDdBasis,
            startingBalance = cfg.startingBalance,
        )
    val stateRoot = StateDir.resolve(sub.option("state-dir")).stateRoot
    val insightsSink = sessionInsightsSink(cfg, stateRoot)
    val handle =
        com.qkt.app
            .LiveSession(
                strategies = identities.map { (id, s) -> id to s } + (BotSessionRecorder.ID to recorder),
                haltRules = haltRules,
                source = sourceFactory(streams.symbols),
                symbols = streams.symbols,
                candleWindow = streams.baseWindow,
                accountingConfig = cfg.accountingConfig,
                equityBasis = cfg.liveEquityBasis,
                brokerFactories = accounts.orderEntry(),
                initialBalance = cfg.startingBalance,
                totalDdBasis = cfg.totalDdBasis,
                dailyDdBasis = cfg.dailyDdBasis,
                runawayMaxRoundTrips = cfg.runawayMaxRoundTrips,
                runawayMaxRejections = cfg.runawayMaxRejections,
                persistor = cfg.statePersistor(stateRoot),
                ignoreMismatches = sub.option("reconcile") == "ignore-mismatches",
                insightsSink = insightsSink,
                insightsEvents = cfg.insights.events,
                insightsStatePollMs = cfg.insights.statePollMs,
                insightsDealBackfillDays = cfg.insights.dealBackfillDays,
            ).start()
    val session =
        BotRunSession(spec.runId, LiveBotRunBackend(handle, identities.keys), identities, streams, history, recorder)
    return try {
        serveSession(
            sub = sub,
            json = json,
            session = session,
            mode = "live",
            cfg = cfg,
            stateRoot = stateRoot,
            quoteContextFor = { symbol ->
                com.qkt.trade.venue.BotVenues.forSymbol(cfg, symbol).use {
                    it.venue.facts(symbol).toQuoteContext(symbol, cfg.accountCurrency)
                }
            },
            serverThreads = 4,
            insightsSink = insightsSink,
            onFinish = {
                BotSessionFiles.delete(stateRoot, spec.runId)
                null
            },
        )
    } finally {
        accounts.close()
    }
}

/** One insights sink for the session and its trail, so a journal directory has one writer; null when off. */
internal fun sessionInsightsSink(
    cfg: com.qkt.cli.Config,
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

/** The newest closed bars from each symbol's venue, so `bot bars` serves history at the start. Best-effort. */
private fun seedLiveWarmup(
    cfg: com.qkt.cli.Config,
    streams: BotSessionStreams,
    historyBars: Int,
    history: BarHistory,
) {
    val nowMs =
        com.qkt.common
            .SystemClock()
            .now()
    streams.symbols.forEach { sym ->
        val bars =
            runCatching {
                com.qkt.trade.venue.BotVenues
                    .forSymbol(cfg, sym)
                    .use { it.venue.bars(sym, streams.baseWindow, historyBars, nowMs) }
            }.getOrElse { e ->
                System.err.println("qkt bot: warmup fetch failed for $sym: ${e.message}")
                emptyList()
            }
        history.seed(sym, bars.filter { it.endTime <= nowMs }.map { it.copy(symbol = sym) })
    }
}
