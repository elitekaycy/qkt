package com.qkt.cli.bot

import com.qkt.backtest.report.BacktestReportWriter
import com.qkt.cli.Args
import com.qkt.cli.BacktestContext
import com.qkt.cli.daemon.StateDir
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionFiles
import com.qkt.trade.session.BotSessionIdentities
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.BotSessionStreams
import com.qkt.trade.session.IntentJournal
import com.qkt.trade.session.ReplayBotRunBackend
import com.qkt.trade.session.RunLock
import com.qkt.trade.session.copySessionJournals
import com.qkt.trade.session.readSessionMeta
import com.qkt.trade.session.writeFills
import java.nio.file.Files
import java.nio.file.Path

/**
 * `session start --backtest`: the replay `qkt backtest` builds over the store, every `qkt backtest` flag
 * applying, with one compiled DSL strategy per identity in the slots. `finish` writes the standard report.
 */
internal fun startBacktestSession(
    sub: Args,
    json: Boolean,
    spec: SessionStart,
): Int {
    val outDir = Path.of(sub.option("out") ?: "runs/${spec.runId}")
    val stateRoot = StateDir.resolve(sub.option("state-dir")).stateRoot
    val sessionDir = BotSessionFiles.sessionDir(stateRoot, spec.runId)
    RunLock.acquire(sessionDir, spec.runId).use { _ ->
        return backtestOnLock(sub, json, spec, outDir, stateRoot, sessionDir)
    }
}

private fun backtestOnLock(
    sub: Args,
    json: Boolean,
    spec: SessionStart,
    outDir: Path,
    stateRoot: Path,
    sessionDir: Path,
): Int {
    // A live run's record is what its next start resumes from; a backtest never takes its id.
    require(readSessionMeta(sessionDir)?.mode != "live") {
        "run ${spec.runId} is a live session's record; pick another --run for a backtest"
    }
    // A backtest run is a fresh record each time it starts; only a live run resumes its own.
    listOf("intents.jsonl", "reads.jsonl", "fills.jsonl").forEach { Files.deleteIfExists(sessionDir.resolve(it)) }
    val parts = buildBacktestSession(sub, spec, IntentJournal(sessionDir.resolve("intents.jsonl")))
    return serveSession(
        sub = sub,
        json = json,
        session = parts.session,
        mode = "backtest",
        cfg = botConfig(sub),
        stateRoot = stateRoot,
        quoteContextFor = null,
        serverThreads = 1,
        onFinish = { result ->
            result?.let {
                Files.createDirectories(outDir)
                BacktestReportWriter(outDir).write(it)
                writeFills(sessionDir, it.trades)
                copySessionJournals(sessionDir, outDir)
                BotSessionFiles.delete(stateRoot, spec.runId)
                outDir.toAbsolutePath().toString()
            }
        },
    )
}

/** A backtest session and the engine and recorder under it, before anything serves or drives it. */
internal class BacktestSessionParts(
    val session: BotRunSession,
    val engine: com.qkt.research.ReplayEngine,
    val recorder: BotSessionRecorder,
)

/** Builds the backtest session `session start --backtest` [sub] describes, writing intents to [journal]. */
internal fun buildBacktestSession(
    sub: Args,
    spec: SessionStart,
    journal: IntentJournal?,
): BacktestSessionParts {
    val streams = spec.streams
    val ast =
        when (val parsed = Dsl.parse(streams.source("botsession"))) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Failure ->
                error(
                    "session streams failed to parse: ${parsed.errors.joinToString { it.message }}",
                )
        }
    val ctx = BacktestContext.build(sub, ast)
    ctx.provision()
    val history = BarHistory(capacity = maxOf(spec.historyBars, 1000))
    val recorder = BotSessionRecorder(history)
    val identities = BotSessionIdentities.compile(streams, spec.identities, history = history)
    val engine =
        ctx
            .backtest(
                emptyMap(),
                strategies =
                    identities.map { (id, s) -> id to s as com.qkt.strategy.Strategy } +
                        (BotSessionRecorder.ID to recorder),
            ).toEngine()
    seedBacktestWarmup(ctx, streams, spec.historyBars, history)
    val session =
        BotRunSession(spec.runId, ReplayBotRunBackend(engine), identities, streams, history, recorder, journal)
    return BacktestSessionParts(session, engine, recorder)
}

/** Best-effort pre-window seed so `bot bars` has history at the first `next`. */
private fun seedBacktestWarmup(
    ctx: BacktestContext,
    streams: BotSessionStreams,
    historyBars: Int,
    history: BarHistory,
) {
    val source = LocalMarketSource(ctx.store, FixedClock(time = ctx.from.toEpochMilli()), barStore = ctx.barStore)
    // 3x span covers weekend/holiday gaps for intraday windows without a calendar walk.
    val spanMs = streams.baseWindow.durationMs * historyBars * 3
    val warmRange = TimeRange(ctx.from.minusMillis(spanMs), ctx.from)
    streams.symbols.forEach { sym ->
        val bars = runCatching { source.bars(sym, streams.baseWindow, warmRange).toList() }.getOrElse { emptyList() }
        history.seed("$sym@${streams.baseTf}", bars.takeLast(historyBars))
    }
}
