package com.qkt.cli

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.cli.observe.PortPrinter
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.cli.observe.TradeDto
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.Trade
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
     * [com.qkt.marketdata.source.CompositeMarketSource] from the loaded MT5 broker
     * profiles plus Bybit public spot/linear sources, with TradingView as fallback.
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

        val effectiveSourceFactory: (List<String>) -> MarketSource =
            sourceFactory ?: run {
                val configPath =
                    args.option("config")?.let { Path.of(it) } ?: Path.of("./qkt.config.yaml")
                val cfg = Config.load(configPath)
                val mt5Profiles =
                    try {
                        com.qkt.broker.mt5
                            .MT5BrokerProfileLoader()
                            .load(
                                raw = cfg.brokers,
                                defaults = com.qkt.broker.mt5.MT5DefaultProfiles.all,
                                env = System.getenv(),
                            )
                    } catch (e: Exception) {
                        println("[WARN] mt5 profile load failed: ${e.message}")
                        emptyList()
                    }
                MarketSourceFactory.composite(mt5Profiles)
            }
        val marketSource = effectiveSourceFactory(symbols)

        println("[INFO] qkt ${BuildInfo.VERSION} — strategy ${ast.name} v${ast.version} — paper-trading")
        println("[INFO] subscribed: ${symbols.joinToString(", ")}")

        val ring = EventRing(capacity = ringSize)
        val startMs = System.currentTimeMillis()
        val startedAt = Instant.ofEpochMilli(startMs).toString()

        val session =
            LiveSession(
                strategies = listOf(ast.name to strategy),
                source = marketSource,
                symbols = symbols,
                candleWindow = candleWindow,
                onTrade = { trade, realized, _ ->
                    val ts = Instant.ofEpochMilli(trade.timestamp)
                    println(
                        "[INFO] $ts ${trade.side} ${trade.symbol} qty=${trade.quantity.toPlainString()} " +
                            "px=${trade.price.toPlainString()} realized=${realized.toPlainString()}",
                    )
                    ring.append("trade", tradeToJson(trade, realized))
                },
                onSignal = { sig ->
                    ring.append("signal", signalToJson(sig))
                },
            ).start()

        val server: ObservabilityServer? =
            if (!noObserve) {
                ObservabilityServer(
                    ring = ring,
                    statusProvider = {
                        val layers =
                            session.pendingStackLayerInfos().map {
                                PendingStackLayer(
                                    stackId = it.stackId,
                                    layer = it.layer,
                                    triggerPrice = it.triggerPrice,
                                    side = it.side,
                                    quantity = it.quantity,
                                )
                            }
                        buildSnapshot(ast.name, ast.version, startMs, startedAt, session.recentTrades(), layers)
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
                ).also { it.start() }
            } else {
                null
            }

        server?.let { PortPrinter.announce(bind, it.boundPort, portFile) }

        val shutdownHook =
            Thread {
                System.err.println("[INFO] graceful shutdown initiated")
                session.stop()
                session.awaitTermination(Duration.ofMillis(shutdownTimeoutMs))
                if (flattenOnStop) {
                    System.err.println("[INFO] flatten-on-stop requested (no-op in 12b paper mode)")
                }
                runCatching { server?.close() }
                System.err.println("[INFO] terminated; ${session.recentTrades().size} trades")
            }
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

    private fun tradeToJson(
        trade: Trade,
        realized: BigDecimal,
    ) = buildJsonObject {
        put("timestamp", JsonPrimitive(Instant.ofEpochMilli(trade.timestamp).toString()))
        put("side", JsonPrimitive(trade.side.name))
        put("symbol", JsonPrimitive(trade.symbol))
        put("qty", JsonPrimitive(trade.quantity.toPlainString()))
        put("price", JsonPrimitive(trade.price.toPlainString()))
        put("realized", JsonPrimitive(realized.toPlainString()))
    }

    private fun signalToJson(sig: com.qkt.strategy.Signal) =
        buildJsonObject {
            when (sig) {
                is com.qkt.strategy.Signal.Buy -> {
                    put("kind", JsonPrimitive("buy"))
                    put("symbol", JsonPrimitive(sig.symbol))
                    put("size", JsonPrimitive(sig.size.toPlainString()))
                }
                is com.qkt.strategy.Signal.Sell -> {
                    put("kind", JsonPrimitive("sell"))
                    put("symbol", JsonPrimitive(sig.symbol))
                    put("size", JsonPrimitive(sig.size.toPlainString()))
                }
                is com.qkt.strategy.Signal.Submit -> {
                    put("kind", JsonPrimitive("submit"))
                    put("symbol", JsonPrimitive(sig.request.symbol))
                    put("size", JsonPrimitive(sig.request.quantity.toPlainString()))
                }
                is com.qkt.strategy.Signal.CancelPendingForSymbol -> {
                    put("kind", JsonPrimitive("cancel_stacks"))
                    put("symbol", JsonPrimitive(sig.symbol))
                }
            }
        }

    private fun buildSnapshot(
        strategyName: String,
        strategyVersion: Int,
        startMs: Long,
        startedAt: String,
        trades: List<Trade>,
        pendingStackLayers: List<PendingStackLayer> = emptyList(),
    ): StatusSnapshot {
        val now = System.currentTimeMillis()
        val last = trades.lastOrNull()
        val realized =
            trades.fold(BigDecimal.ZERO) { acc, _ -> acc }
        return StatusSnapshot(
            strategy = strategyName,
            version = strategyVersion,
            uptimeMs = now - startMs,
            startedAt = startedAt,
            equity = BigDecimal.ZERO,
            balance = BigDecimal.ZERO,
            realized = realized,
            unrealized = BigDecimal.ZERO,
            positions = emptyList<PositionDto>(),
            lastTrade =
                last?.let {
                    TradeDto(
                        timestamp = Instant.ofEpochMilli(it.timestamp).toString(),
                        side = it.side.name,
                        symbol = it.symbol,
                        qty = it.quantity,
                        price = it.price,
                        realized = BigDecimal.ZERO,
                    )
                },
            pendingStackLayers = pendingStackLayers,
        )
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun defaultTradingViewSource(symbols: List<String>): MarketSource = TradingViewMarketSource.connect()
    }
}
