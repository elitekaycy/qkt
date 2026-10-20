package com.qkt.cli.bot

import com.qkt.backtest.report.BacktestReportWriter
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.golden.GoldenReplayStoreWriter
import com.qkt.cli.golden.RecordedTick
import com.qkt.common.SystemClock
import com.qkt.trade.session.IntentJournal
import com.qkt.trade.session.IntentReplay
import com.qkt.trade.session.LiveTickLog
import com.qkt.trade.session.ReplayDiff
import com.qkt.trade.session.VenueDealLog
import com.qkt.trade.session.fillsOf
import com.qkt.trade.session.firedTickIndex
import com.qkt.trade.session.measuredVenueLatencyMs
import com.qkt.trade.session.readFills
import com.qkt.trade.session.readSessionMeta
import com.qkt.trade.session.venueFillIndex
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * `qkt bot session replay <dir> [--out <dir>] [--from --to --data-root --no-fetch --config]`: builds the
 * backtest session the record in [dir] declared (a run's report directory, or its state session directory)
 * over the store, re-injects every journaled intent where it arrived, writes the standard report to
 * `--out`, and `replay-diff.json`: each recorded fill beside the replayed one. A backtest record replays
 * identically; a live record replays over the ticks it recorded (`ticks.csv`), else over stored ticks, and the
 * diff shows where live differed (`sameTrades`: the same trades, at the venue's own prices and times).
 */
internal class BotReplayCommand(
    private val args: Args,
) {
    fun run(): Int = botRun(json = true) { replay() }

    private fun replay(): Int {
        val dir = Path.of(args.requirePositional(0, "<report or session directory>"))
        val meta =
            readSessionMeta(dir)
                ?: error("no session record (session.meta.json) in $dir; pass a run's report or session directory")
        val intents = IntentJournal.read(dir.resolve("intents.jsonl"))
        val out = Path.of(args.option("out") ?: "$dir-replay")
        val live = meta.mode == "live"
        val ticks =
            if (live &&
                args.option("data-root") == null &&
                !args.flag("bars")
            ) {
                recordedTicks(dir)
            } else {
                emptyList()
            }
        val recordedTicks = ticks.takeIf { it.isNotEmpty() }?.let { writeTickStore(it, out) }
        // The pipeline hands the session ticks before it records its start: the replay starts with the first.
        val startMs = minOf(meta.startedMs, ticks.firstOrNull()?.timestamp ?: meta.startedMs)
        val tokens = replayTokens(meta.argv, live, intents.maxOfOrNull { it.simMs }, startMs)
        // The recorded tick each intent fired on: its fate says, else the first tick stamped after it arrived.
        val firedIndex = firedTickIndex(intents, IntentJournal.firedTickMs(dir.resolve("intents.jsonl")), ticks)
        val firedOn = firedIndex.mapValues { (_, i) -> ticks[i].timestamp }
        val filledAt = IntentJournal.filledAt(dir.resolve("intents.jsonl"))
        // A fill is booked when the venue's confirmation arrives; the venue's deals say when it executed.
        val deals = VenueDealLog.read(dir.resolve(VenueDealLog.FILE))
        // What happened (a trace: each fill at the quote the venue executed it at) when the record can say,
        // or `--trace`; `--predict` replays at the median round trip instead: what would happen.
        val trace = ticks.isNotEmpty() && !args.flag("predict") && (args.flag("trace") || deals.isNotEmpty())
        val executedAt = VenueDealLog.executedAt(filledAt, readFills(dir.resolve("fills.jsonl")), deals)
        val enterAt = if (trace) firedIndex + venueFillIndex(executedAt, ticks) else firedIndex
        // A venue fills at its quote (ask to buy, bid to sell): over the recorded quotes, so does mt5-sim.
        val venueFills = if (args.option("broker") == null) listOf("--broker", "mt5-sim") else emptyList()
        // And it fills when the order reaches it: the round trip the record measured, unless one is given.
        val latencyMs =
            if (args.option("execution-latency") == null && !trace) {
                measuredVenueLatencyMs(firedOn, filledAt)
            } else {
                null
            }
        val venueLatency = latencyMs?.let { listOf("--execution-latency", "${it}ms") }.orEmpty()
        val overStore =
            recordedTicks?.let {
                listOf("--data-root", it.toString(), "--no-fetch", "--allow-incomplete") + venueFills + venueLatency
            }
        val sub = Args((tokens + overStore.orEmpty()).toTypedArray())
        val spec = parseSessionStart(sub) { meta.runId }
        val parts = buildBacktestSession(sub, spec, journal = null)
        val finish = IntentJournal.finishPoint(dir.resolve("intents.jsonl"))
        val result =
            if (recordedTicks != null) {
                // Live, an intent fires on a tick later than its tickSeq says (ticks the engine had not run yet).
                // Over those same ticks, it re-enters just before the tick it fired on.
                fun before(ms: Long) = ticks.count { it.timestamp <= ms }.toLong()
                IntentReplay(parts.engine, parts.recorder, parts.session).run(
                    intents.map { it.copy(tickSeq = enterAt[it.intentId]?.toLong() ?: before(it.simMs)) },
                    byArrivalTime = false,
                    finishedAt = finish?.let { (ms, _) -> ms to before(ms) },
                )
            } else {
                IntentReplay(parts.engine, parts.recorder, parts.session)
                    .run(intents, byArrivalTime = live, finishedAt = finish)
            }
        Files.createDirectories(out)
        BacktestReportWriter(out).write(result)
        val diff = ReplayDiff.of(readFills(dir.resolve("fills.jsonl")), fillsOf(result.trades))
        Files.writeString(out.resolve("replay-diff.json"), diff.json() + "\n")
        println(
            jsonObj(
                "ok" to true,
                "run" to meta.runId,
                "mode" to meta.mode,
                "intents" to intents.size,
                "identical" to diff.identical,
                "sameTrades" to diff.sameTrades,
                "samePrices" to diff.samePrices,
                "market" to if (recordedTicks != null) "recorded ticks" else "store",
                "latencyMs" to if (recordedTicks != null) latencyMs else null,
                "venueDeals" to if (trace) deals.size else null,
                "replay" to
                    when {
                        trace -> "trace"
                        recordedTicks != null -> "venue-latency"
                        else -> "store"
                    },
                "recordedFills" to diff.recorded.size,
                "replayedFills" to diff.replayed.size,
                "out" to out.toAbsolutePath().toString(),
            ),
        )
        return ExitCodes.SUCCESS
    }

    /**
     * A live record's own ticks (`ticks.csv`): the replay runs over the market the session traded on. They span
     * the session alone, so the rest of its day is no gap.
     */
    private fun recordedTicks(dir: Path) = LiveTickLog.read(dir.resolve(LiveTickLog.FILE))

    /** [ticks] as a private tick store under [out]. */
    private fun writeTickStore(
        ticks: List<com.qkt.marketdata.Tick>,
        out: Path,
    ): Path {
        val root = out.resolve("recorded-ticks")
        GoldenReplayStoreWriter(SystemClock())
            .writeTickStore(root, ticks.mapIndexed { i, t -> RecordedTick(i.toLong(), false, null, t) })
        return root
    }

    /**
     * The start arguments of the record with the replay's own overrides in place of theirs. A live record
     * becomes a backtest from its start to an hour past its last intent unless `--from/--to` say otherwise.
     */
    private fun replayTokens(
        original: List<String>,
        live: Boolean,
        lastIntentMs: Long?,
        startedMs: Long,
    ): List<String> {
        val overridden = OVERRIDES.filter { args.option(it) != null || args.flag(it) }.map { "--$it" }.toSet()
        val kept = ArrayList<String>()
        var i = 0
        while (i < original.size) {
            val token = original[i]
            val takesValue = i + 1 < original.size && !original[i + 1].startsWith("--")
            if (token in overridden || token == "--json") {
                i += if (takesValue && token.removePrefix("--") !in FLAGS) 2 else 1
                continue
            }
            kept += token
            i++
        }
        val added =
            OVERRIDES.flatMap { name ->
                when {
                    name in FLAGS -> if (args.flag(name)) listOf("--$name") else emptyList()
                    args.option(name) != null -> listOf("--$name", args.option(name)!!)
                    else -> emptyList()
                }
            }
        val window =
            if (live && args.option("from") == null) {
                val end = (lastIntentMs ?: startedMs) + 3_600_000L
                listOf(
                    "--backtest",
                    "--from",
                    Instant.ofEpochMilli(startedMs).toString(),
                    "--to",
                    Instant.ofEpochMilli(end).toString(),
                )
            } else {
                emptyList()
            }
        return kept + added + window
    }

    private companion object {
        val FLAGS = setOf("no-fetch", "bars", "tick-fills")
        val OVERRIDES = listOf("from", "to", "data-root", "config", "broker", "execution-latency") + FLAGS
    }
}
