package com.qkt.cli

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.cli.daemon.signalToJson
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PortPrinter
import com.qkt.common.coverage.ExcludeFromCoverage
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.MarketSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * `qkt run <strategy.qkt>` — foreground paper-trading of a single strategy.
 *
 * The strategy runs in the current process and exits when the user terminates with
 * Ctrl-C. Useful for development and one-off manual runs. For long-lived operation,
 * use `qkt daemon` + `qkt deploy`.
 */
class RunCommand(
    private val args: Args,
    /**
     * Test seam. When `null` (production default), the run command builds a
     * [com.qkt.marketdata.source.CompositeMarketSource] from the configured trading accounts'
     * own feeds, with TradingView as fallback.
     * Tests pass an explicit factory to swap in a fake.
     */
    private val sourceFactory: ((List<String>) -> MarketSource)? = null,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        val ast =
            when (val parsed = Dsl.parseFile(path)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure -> {
                    for (e in parsed.errors) System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                    System.err.println("${parsed.errors.size} error${if (parsed.errors.size != 1) "s" else ""}")
                    return ExitCodes.USER_ERROR
                }
            }

        val source = args.option("source") ?: "tv"
        if (source != "tv") {
            System.err.println(
                "qkt: error: live broker execution ('--source $source') is not yet enabled in 12a; " +
                    "use --source tv (default) for paper-trading on live TradingView ticks.",
            )
            return ExitCodes.USER_ERROR
        }

        val port = args.option("port")?.toIntOrNull() ?: 0
        val allowPrivileged = args.flag("allow-privileged-port")
        if (port in 1..1023 && !allowPrivileged) {
            System.err.println(
                "qkt: error: port $port is privileged (< 1024); add --allow-privileged-port to override.",
            )
            return ExitCodes.ARG_ERROR
        }
        val bind = args.option("bind") ?: "127.0.0.1"
        val portFile = args.option("port-file")?.let { Path.of(it) }
        val ringSize = args.option("ring-size")?.toIntOrNull() ?: 1000
        val noObserve = args.flag("no-observe")

        val shutdownTimeoutMs =
            args.option("shutdown-timeout")?.toLongOrNull() ?: 5_000L
        val flattenOnStop = args.flag("flatten-on-stop")

        if (!noObserve && bind != "127.0.0.1" && bind != "localhost") {
            System.err.println(
                "[WARN] --bind $bind: observability server is reachable from any host. There is NO authentication.",
            )
            System.err.println("       Front with nginx + basic auth + TLS for production exposure.")
        }

        val strategy = AstCompiler().compile(ast)
        val symbols = ast.streams.map { it.qktSymbol }.distinct()
        val candleWindow: TimeWindow? =
            ast.streams
                .firstOrNull()
                ?.timeframe
                ?.let { TimeWindow.parse(it) }

        var accountingConfig = com.qkt.accounting.AccountingConfig()
        var liveEquityBasis = com.qkt.app.LiveEquityBasis.VENUE
        var runawayMaxRoundTrips = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS
        var runawayMaxRejections = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS
        var candleCloseGraceMs = com.qkt.app.LiveSession.DEFAULT_CANDLE_CLOSE_GRACE_MS
        val effectiveSourceFactory: (List<String>) -> MarketSource =
            sourceFactory ?: run {
                val configPath = Config.resolvePath(args.option("config"))
                val cfg = Config.load(configPath)
                accountingConfig = cfg.accountingConfig
                liveEquityBasis = cfg.liveEquityBasis
                runawayMaxRoundTrips = cfg.runawayMaxRoundTrips
                runawayMaxRejections = cfg.runawayMaxRejections
                candleCloseGraceMs = cfg.candleCloseGraceMs
                MarketSourceFactory.composite(cfg.accountMarketDataRoutes(), cfg.source, hub = cfg.hub)
            }
        val feedSymbols = (symbols + accountingConfig.normalizedSymbols.values).distinct()
        val marketSource = effectiveSourceFactory(feedSymbols)

        printRunBanner(ast.name, ast.version, feedSymbols)

        val ring = EventRing(capacity = ringSize)
        val startMs = System.currentTimeMillis()
        val startedAt = Instant.ofEpochMilli(startMs).toString()

        val session =
            LiveSession(
                strategies = listOf(ast.name to strategy),
                source = marketSource,
                symbols = symbols,
                feedSymbols = feedSymbols,
                candleWindow = candleWindow,
                accountingConfig = accountingConfig,
                equityBasis = liveEquityBasis,
                runawayMaxRoundTrips = runawayMaxRoundTrips,
                runawayMaxRejections = runawayMaxRejections,
                candleCloseGraceMs = candleCloseGraceMs,
                onTrade = tradeRecorder(ring),
                onSignal = { sig ->
                    ring.append("signal", signalToJson(sig))
                },
            ).start()

        val server: ObservabilityServer? =
            if (!noObserve) {
                ObservabilityServer(
                    ring = ring,
                    statusProvider = {
                        val layers = pendingStackLayers(session.pendingStackLayerInfos())
                        buildSnapshot(
                            ast.name,
                            ast.version,
                            startMs,
                            startedAt,
                            session.recentTrades(),
                            layers,
                            pnl = session.pnlSnapshot(ast.name),
                            persistenceHealth = session.persistenceHealth(),
                        )
                    },
                    running = { session.running },
                    onStop = { flatten ->
                        if (flatten) {
                            System.err.println("[INFO] flatten-on-stop requested via /stop (no-op in 12b paper mode)")
                        }
                        session.stop()
                    },
                    bind = bind,
                    port = port,
                    latencyProvider = { session.latencySnapshot() },
                ).also { it.start() }
            } else {
                null
            }

        server?.let { PortPrinter.announce(bind, it.boundPort, portFile) }

        val shutdownHook =
            Thread { gracefulShutdown(session, server, shutdownTimeoutMs, flattenOnStop) }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        return try {
            val terminated = session.awaitTermination(Duration.ofDays(365))
            if (terminated) {
                println("[INFO] feed closed; ${session.recentTrades().size} trades")
            }
            ExitCodes.SUCCESS
        } finally {
            runCatching { server?.close() }
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        @ExcludeFromCoverage("opens the live TradingView websocket; only a running process reaches it")
        fun defaultTradingViewSource(symbols: List<String>): MarketSource = TradingViewMarketSource.connect()
    }
}
