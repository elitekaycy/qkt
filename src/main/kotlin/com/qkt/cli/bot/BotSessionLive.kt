package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.createLiveSession
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionFiles
import com.qkt.trade.session.BotSessionIdentities
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.BotSessionStreams
import com.qkt.trade.session.IntentJournal
import com.qkt.trade.session.LiveBotRunBackend
import com.qkt.trade.session.LiveSessionRecord
import com.qkt.trade.session.LiveTickLog
import com.qkt.trade.session.RunLock
import com.qkt.trade.session.copySessionJournals
import com.qkt.trade.session.readSessionMeta
import com.qkt.trade.session.rebuildIdentities
import com.qkt.trade.venue.toQuoteContext
import java.nio.file.Path

/**
 * `session start` without `--backtest`: the deploy pipeline over the configured accounts
 * ([LiveSessionStack]), one compiled DSL strategy per identity. A run that resumes (`--run` of an earlier
 * live session over the same state) first recompiles every intent it journaled, so the exits the engine
 * holds for its open legs restore. `finish` writes `summary.json` and copies the journals to `--out`.
 */
internal fun startLiveSession(
    sub: Args,
    json: Boolean,
    spec: SessionStart,
): Int {
    val configPath = Config.resolvePath(sub.option("config"))
    val stateDir = StateDir.resolve(sub.option("state-dir"))
    refuseDeployedIdentities(spec.identities, daemonStrategyNames(stateDir))
    val sessionDir = BotSessionFiles.sessionDir(stateDir.stateRoot, spec.runId)
    RunLock.acquire(sessionDir, spec.runId).use { _ ->
        return liveOnLock(sub, json, spec, configPath, stateDir, sessionDir)
    }
}

private fun liveOnLock(
    sub: Args,
    json: Boolean,
    spec: SessionStart,
    configPath: Path,
    stateDir: StateDir,
    sessionDir: Path,
): Int {
    val streams = spec.streams
    LiveSessionStack(Config.load(configPath), configPath, stateDir).use { stack ->
        val cfg = stack.cfg
        val outDir = Path.of(sub.option("out") ?: "runs/${spec.runId}")
        val history = BarHistory(capacity = maxOf(spec.historyBars, 1000))
        seedLiveWarmup(cfg, streams, spec.historyBars, history)
        val tickLog = LiveTickLog(sessionDir.resolve(LiveTickLog.FILE))
        val recorder = BotSessionRecorder(history, tickLog::append)
        val identities = BotSessionIdentities.compile(streams, spec.identities, history = history)
        val record = LiveSessionRecord(sessionDir)
        val journal = IntentJournal(sessionDir.resolve("intents.jsonl"))
        val earlier = IntentJournal.read(journal.path)
        // Before the pipeline starts: its restore checks every persisted exit hook against the strategy.
        rebuildIdentities(earlier, streams, identities)
        val handle =
            stack.factory.createLiveSession(
                name = spec.runId,
                strategies = identities.map { (id, s) -> id to s } + (BotSessionRecorder.ID to recorder),
                symbols = streams.symbols,
                candleWindow = streams.baseWindow,
                ignoreMismatches = sub.option("reconcile") == "ignore-mismatches",
                onTrade = { trade, realized, identity -> record.fill(trade, realized, identity) },
            )
        val session =
            BotRunSession(
                spec.runId,
                LiveBotRunBackend(handle, identities.keys),
                identities,
                streams,
                history,
                recorder,
                journal,
            )
        session.restoreFates(earlier)
        if (earlier.isNotEmpty()) {
            System.err.println(
                "qkt bot: resumed ${earlier.size} journaled intents of ${spec.runId}",
            )
        }
        return serveSession(
            sub = sub,
            json = json,
            session = session,
            mode = "live",
            cfg = cfg,
            stateRoot = stateDir.stateRoot,
            quoteContextFor = { symbol ->
                com.qkt.trade.venue.BotVenues.forSymbol(cfg, symbol).use {
                    it.venue.facts(symbol).toQuoteContext(symbol, cfg.accountCurrency)
                }
            },
            serverThreads = 4,
            insightsSink = stack.insightsSink,
            onFinish = {
                record.summary(identities.keys, session.legs())
                tickLog.close()
                // From before the session's recorded start: the pipeline runs (and can fill) ahead of it.
                val from = (readSessionMeta(sessionDir)?.startedMs ?: 0L) - 120_000L
                captureVenueDeals(cfg, streams, sessionDir, from)
                captureVenueInstruments(cfg, streams, sessionDir)
                copySessionJournals(sessionDir, outDir)
                BotSessionFiles.delete(stateDir.stateRoot, spec.runId)
                outDir.toAbsolutePath().toString()
            },
        )
    }
}

/** The newest closed bars from each symbol's venue, so `bot bars` serves history at the start. Best-effort. */
private fun seedLiveWarmup(
    cfg: Config,
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
        history.seed("$sym@${streams.baseTf}", bars.filter { it.endTime <= nowMs }.map { it.copy(symbol = sym) })
    }
}
