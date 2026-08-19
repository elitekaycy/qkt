package com.qkt.cli.bot

import com.qkt.backtest.report.BacktestReportWriter
import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.BacktestContext
import com.qkt.cli.ExitCodes
import com.qkt.cli.daemon.StateDir
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotBridgeStrategy
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionDescriptor
import com.qkt.trade.session.BotSessionFiles
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.BotSessionServer
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * `qkt bot session start|status|finish` — lifecycle of a bot run session.
 *
 * `start --backtest` assembles the same store-backed replay `qkt backtest` uses
 * (data provisioning, config-driven halt rules, instruments) but with bridge
 * strategies in the strategy slots, then serves the session over local HTTP until
 * `finish` writes the standard report artifacts to `--out`. The command runs in the
 * foreground; run it under `&`, tmux, or a container for a long session.
 */
class BotSessionCommand(
    private val args: Args,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) {
            when (val sub = args.firstNonOption()) {
                "start" -> start(Args(args.tokens.drop(1).toTypedArray()), json)
                "status" -> status(json)
                "finish" -> finish(json)
                else -> error("usage: qkt bot session start|status|finish (got '${sub ?: ""}')")
            }
        }
    }

    private fun start(
        sub: Args,
        json: Boolean,
    ): Int {
        require(sub.flag("backtest")) {
            "only --backtest sessions are supported so far; live sessions land next " +
                "(one-shot live verbs keep working without a session)"
        }
        val symbols =
            (sub.option("symbols") ?: error("missing --symbols BROKER:SYMBOL[,..]"))
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        require(symbols.isNotEmpty()) { "missing --symbols" }
        symbols.forEach { require(it.contains(':')) { "symbol must be BROKER:SYMBOL, got '$it'" } }
        val tf = sub.option("tf") ?: error("missing --tf (e.g. --tf 5m)")
        val window = TimeWindow.parse(tf)
        val runId = sub.option("run") ?: "run-${sub.requireOption("from")}-${sub.requireOption("to")}"
        val identities =
            (sub.option("identities") ?: "manual")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val outDir = Path.of(sub.option("out") ?: "runs/$runId")
        val historyBars = sub.option("history-bars")?.toIntOrNull() ?: 200

        val ast =
            when (val parsed = Dsl.parse(sessionSource(symbols, tf))) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure ->
                    error("internal session strategy failed to parse: ${parsed.errors.joinToString { it.message }}")
            }
        val ctx = BacktestContext.build(sub, ast)
        ctx.provision()

        val history = BarHistory(capacity = maxOf(historyBars, 1000))
        val recorder = BotSessionRecorder(history)
        val bridges = identities.associateWith { BotBridgeStrategy() }
        val engine =
            ctx
                .backtest(
                    emptyMap(),
                    strategies =
                        bridges.map { (id, b) -> id to b as com.qkt.strategy.Strategy } +
                            (BotSessionRecorder.ID to recorder),
                ).toEngine()
        seedWarmup(ctx, symbols, window, historyBars, history)

        val session =
            BotRunSession(
                runId = runId,
                engine = engine,
                bridges = bridges,
                history = history,
                recorder = recorder,
            )
        val stateRoot = StateDir.resolve(sub.option("state-dir")).stateRoot
        val token = randomToken()
        val cfg = botConfig(sub)
        val server =
            BotSessionServer(
                session = session,
                token = token,
                accountCurrency = cfg.accountCurrency,
                onFinish = { result ->
                    Files.createDirectories(outDir)
                    BacktestReportWriter(outDir).write(result)
                    BotSessionFiles.delete(stateRoot, runId)
                    outDir.toAbsolutePath().toString()
                },
            )
        server.start()
        val descriptor =
            BotSessionDescriptor(
                runId = runId,
                port = server.boundPort,
                token = token,
                mode = "backtest",
                pid = ProcessHandle.current().pid(),
            )
        BotSessionFiles.write(stateRoot, descriptor)
        val started =
            jsonObj(
                "ok" to true,
                "run" to runId,
                "mode" to "backtest",
                "port" to server.boundPort,
                "identities" to identities,
                "out" to outDir.toString(),
            )
        if (json) println(started) else println("session $runId listening on 127.0.0.1:${server.boundPort}")
        try {
            while (!server.finished) Thread.sleep(200L)
        } finally {
            BotSessionFiles.delete(stateRoot, runId)
            server.close()
        }
        return ExitCodes.SUCCESS
    }

    /** Best-effort pre-window seed so `bot bars` has history at the first `next`. */
    private fun seedWarmup(
        ctx: BacktestContext,
        symbols: List<String>,
        window: TimeWindow,
        historyBars: Int,
        history: BarHistory,
    ) {
        val source =
            LocalMarketSource(ctx.store, FixedClock(time = ctx.from.toEpochMilli()), barStore = ctx.barStore)
        // 3x span covers weekend/holiday gaps for intraday windows without a calendar walk.
        val spanMs = window.durationMs * historyBars * 3
        val warmRange =
            TimeRange(
                ctx.from.minusMillis(spanMs),
                ctx.from,
            )
        symbols.forEach { sym ->
            val bars =
                runCatching { source.bars(sym, window, warmRange).toList() }.getOrElse { emptyList() }
            history.seed(sym, bars.takeLast(historyBars))
        }
    }

    private fun status(json: Boolean): Int {
        val client = BotSessionClient.resolve(args) ?: error("no bot session running")
        println(client.get("/status"))
        return ExitCodes.SUCCESS
    }

    private fun finish(json: Boolean): Int {
        val client = BotSessionClient.resolve(args) ?: error("no bot session running")
        println(client.post("/finish", "{}"))
        return ExitCodes.SUCCESS
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Renders the internal never-firing strategy that declares the session's streams. */
    internal fun sessionSource(
        symbols: List<String>,
        tf: String,
    ): String {
        val streams =
            symbols
                .mapIndexed { i, sym -> "    s$i = $sym EVERY $tf" }
                .joinToString("\n")
        return """
            |STRATEGY botsession VERSION 1
            |
            |SYMBOLS
            |$streams
            |
            |RULES
            |    WHEN 1 > 2
            |    THEN BUY s0 SIZING 0.01
            |
            """.trimMargin()
    }
}
