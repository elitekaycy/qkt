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
import com.qkt.trade.session.LiveBotRunBackend
import com.qkt.trade.session.ReplayBotRunBackend
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

    private data class StartSpec(
        val symbols: List<String>,
        val tf: String,
        val window: TimeWindow,
        val runId: String,
        val identities: List<String>,
        val historyBars: Int,
    )

    private fun parseStart(
        sub: Args,
        defaultRun: () -> String,
    ): StartSpec {
        val symbols =
            (sub.option("symbols") ?: error("missing --symbols BROKER:SYMBOL[,..]"))
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        require(symbols.isNotEmpty()) { "missing --symbols" }
        symbols.forEach { require(it.contains(':')) { "symbol must be BROKER:SYMBOL, got '$it'" } }
        val tf = sub.option("tf") ?: error("missing --tf (e.g. --tf 5m)")
        val identities =
            (sub.option("identities") ?: "manual")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return StartSpec(
            symbols = symbols,
            tf = tf,
            window = TimeWindow.parse(tf),
            runId = sub.option("run") ?: defaultRun(),
            identities = identities,
            historyBars = sub.option("history-bars")?.toIntOrNull() ?: 200,
        )
    }

    private fun start(
        sub: Args,
        json: Boolean,
    ): Int {
        if (!sub.flag("backtest")) return startLive(sub, json)
        val spec = parseStart(sub) { "run-${sub.requireOption("from")}-${sub.requireOption("to")}" }
        val (symbols, tf, window, runId, identities, historyBars) = spec
        val outDir = Path.of(sub.option("out") ?: "runs/$runId")

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
                backend = ReplayBotRunBackend(engine),
                bridges = bridges,
                history = history,
                recorder = recorder,
            )
        val stateRoot = StateDir.resolve(sub.option("state-dir")).stateRoot
        val cfg = botConfig(sub)
        return serve(
            sub = sub,
            json = json,
            session = session,
            mode = "backtest",
            cfg = cfg,
            stateRoot = stateRoot,
            identities = identities,
            quoteContextFor = null,
            serverThreads = 1,
            onFinish = { result ->
                if (result == null) {
                    null
                } else {
                    Files.createDirectories(outDir)
                    BacktestReportWriter(outDir).write(result)
                    BotSessionFiles.delete(stateRoot, runId)
                    outDir.toAbsolutePath().toString()
                }
            },
        )
    }

    /**
     * Live session: the same pipeline `qkt deploy` runs (LiveSession, MT5 broker,
     * config halt rules), with bridge strategies in the slots. Intents compile
     * against venue point-in-time facts via [com.qkt.trade.BotGateway], so sizing
     * and quantization match the one-shot live path exactly.
     */
    private fun startLive(
        sub: Args,
        json: Boolean,
    ): Int {
        val spec = parseStart(sub) { "live-${com.qkt.common.SystemClock().now()}" }
        val (symbols, _, window, runId, identities, historyBars) = spec
        val cfg = botConfig(sub)
        val profiles =
            com.qkt.broker.mt5.MT5BrokerProfileLoader().load(
                raw = cfg.brokers,
                defaults = com.qkt.broker.mt5.MT5DefaultProfiles.all,
                env = System.getenv(),
                calendars = cfg.brokerCalendars,
                aliases = cfg.brokerAliases,
                capabilityRestrictions = cfg.brokerCapabilityRestrictions,
                instrumentOverrides = cfg.brokerInstrumentOverrides,
            )
        require(profiles.isNotEmpty()) { "no MT5 broker profiles in config — a live session needs a gateway" }
        val sourceFactory =
            com.qkt.cli.MarketSourceFactory
                .composite(profiles, source = cfg.source)
        val brokerFactories: Map<String, com.qkt.app.BrokerFactory> =
            profiles.associate { profile ->
                profile.name.lowercase() to
                    { bus, clock, priceTracker, _, strategyName ->
                        com.qkt.broker.mt5.MT5Broker(
                            profile = profile,
                            bus = bus,
                            clock = clock,
                            priceTracker = priceTracker,
                            strategyName = strategyName,
                        )
                    }
            }
        val history = BarHistory(capacity = maxOf(historyBars, 1000))
        seedLiveWarmup(cfg, symbols, window, historyBars, history)
        val recorder = BotSessionRecorder(history)
        val bridges = identities.associateWith { BotBridgeStrategy() }
        val strategies: List<Pair<String, com.qkt.strategy.Strategy>> =
            bridges.map { (id, b) -> id to b } + (BotSessionRecorder.ID to recorder)
        val haltRules =
            com.qkt.risk.HaltRules.standard(
                maxDailyLoss = cfg.maxDailyLoss,
                maxDrawdownPct = cfg.maxDrawdownPct,
                maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                totalDdBasis = cfg.totalDdBasis,
                startingBalance = cfg.startingBalance,
            )
        val handle =
            com.qkt.app
                .LiveSession(
                    strategies = strategies,
                    haltRules = haltRules,
                    source = sourceFactory(symbols),
                    symbols = symbols,
                    candleWindow = window,
                    accountingConfig = cfg.accountingConfig,
                    equityBasis = cfg.liveEquityBasis,
                    brokerFactories = brokerFactories,
                    initialBalance = cfg.startingBalance,
                    totalDdBasis = cfg.totalDdBasis,
                    dailyDdBasis = cfg.dailyDdBasis,
                    runawayMaxRoundTrips = cfg.runawayMaxRoundTrips,
                    runawayMaxRejections = cfg.runawayMaxRejections,
                ).start()
        val session =
            BotRunSession(
                runId = runId,
                backend = LiveBotRunBackend(handle, bridges.keys),
                bridges = bridges,
                history = history,
                recorder = recorder,
            )
        val stateRoot = StateDir.resolve(sub.option("state-dir")).stateRoot
        return serve(
            sub = sub,
            json = json,
            session = session,
            mode = "live",
            cfg = cfg,
            stateRoot = stateRoot,
            identities = identities,
            quoteContextFor = { symbol ->
                com.qkt.trade.BotGateway
                    .forSymbol(cfg, symbol)
                    .quoteContext(symbol, cfg.accountCurrency)
            },
            serverThreads = 4,
            onFinish = {
                BotSessionFiles.delete(stateRoot, runId)
                null
            },
        )
    }

    private fun serve(
        sub: Args,
        json: Boolean,
        session: BotRunSession,
        mode: String,
        cfg: com.qkt.cli.Config,
        stateRoot: Path,
        identities: List<String>,
        quoteContextFor: ((String) -> com.qkt.trade.BotQuoteContext)?,
        serverThreads: Int,
        onFinish: (com.qkt.backtest.BacktestResult?) -> String?,
    ): Int {
        val token = randomToken()
        val sessionDir = BotSessionFiles.sessionDir(stateRoot, session.runId)
        Files.createDirectories(sessionDir)
        val trail =
            com.qkt.trade.BotTrail(stateRoot, cfg.insights, com.qkt.common.SystemClock(), run = session.runId)
        val server =
            BotSessionServer(
                session = session,
                token = token,
                accountCurrency = cfg.accountCurrency,
                onFinish = onFinish,
                quoteContextFor = quoteContextFor,
                serverThreads = serverThreads,
                readsJournal = sessionDir.resolve("reads.jsonl"),
                trail = trail,
            )
        server.start()
        BotSessionFiles.write(
            stateRoot,
            BotSessionDescriptor(
                runId = session.runId,
                port = server.boundPort,
                token = token,
                mode = mode,
                pid = ProcessHandle.current().pid(),
            ),
        )
        val started =
            jsonObj(
                "ok" to true,
                "run" to session.runId,
                "mode" to mode,
                "port" to server.boundPort,
                "identities" to identities,
            )
        if (json) println(started) else println("session ${session.runId} listening on 127.0.0.1:${server.boundPort}")
        try {
            while (!server.finished) Thread.sleep(200L)
        } finally {
            BotSessionFiles.delete(stateRoot, session.runId)
            server.close()
            trail.close()
        }
        return ExitCodes.SUCCESS
    }

    /**
     * Live warmup: fetch the newest closed bars from the venue gateway (the same read
     * the one-shot `bot bars` uses) so `bot bars --count N` serves full history at
     * session start instead of growing one bar per live close. Bars are re-keyed to
     * the session's qkt symbol (the gateway returns broker symbols) and the still-
     * forming bar is dropped. Runs before the live feed starts, so seeded history
     * always precedes live closes in the buffer. Best-effort: a gateway hiccup
     * leaves the cache empty rather than blocking the session.
     */
    private fun seedLiveWarmup(
        cfg: com.qkt.cli.Config,
        symbols: List<String>,
        window: TimeWindow,
        historyBars: Int,
        history: BarHistory,
    ) {
        val nowMs =
            com.qkt.common
                .SystemClock()
                .now()
        symbols.forEach { sym ->
            val bars =
                runCatching {
                    com.qkt.trade.BotGateway
                        .forSymbol(cfg, sym)
                        .bars(sym, window, historyBars, nowMs)
                }.getOrElse { e ->
                    System.err.println("qkt bot: warmup fetch failed for $sym: ${e.message}")
                    emptyList()
                }
            val closed = bars.filter { it.endTime <= nowMs }.map { it.copy(symbol = sym) }
            history.seed(sym, closed)
        }
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
