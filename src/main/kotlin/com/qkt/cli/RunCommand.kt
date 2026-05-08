package com.qkt.cli

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.MarketSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class RunCommand(
    private val args: Args,
    private val sourceFactory: (List<String>) -> MarketSource = ::defaultTradingViewSource,
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

        val shutdownTimeoutMs =
            args.option("shutdown-timeout")?.toLongOrNull() ?: 5_000L
        val flattenOnStop = args.flag("flatten-on-stop")

        val strategy = AstCompiler().compile(ast)
        val symbols = ast.streams.map { it.symbol }.distinct()
        val tvSymbols = ast.streams.map { "${it.broker}:${it.symbol}" }.distinct()
        val candleWindow: TimeWindow? =
            ast.streams
                .firstOrNull()
                ?.timeframe
                ?.let { TimeWindow.parse(it) }

        val marketSource = sourceFactory(tvSymbols)

        println("[INFO] qkt ${BuildInfo.VERSION} — strategy ${ast.name} v${ast.version} — paper-trading")
        println("[INFO] subscribed: ${tvSymbols.joinToString(", ")}")

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
                },
            ).start()

        val shutdownHook =
            Thread {
                System.err.println("[INFO] graceful shutdown initiated")
                session.stop()
                session.awaitTermination(Duration.ofMillis(shutdownTimeoutMs))
                if (flattenOnStop) {
                    System.err.println("[INFO] flatten-on-stop requested (no-op in 12a paper mode)")
                }
                System.err.println("[INFO] terminated; ${session.recentTrades().size} trades")
            }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        // Wait for the feed to terminate naturally (test/bounded feed) or for SIGINT.
        return try {
            val terminated = session.awaitTermination(Duration.ofDays(365))
            if (terminated) {
                println("[INFO] feed closed; ${session.recentTrades().size} trades")
            }
            ExitCodes.SUCCESS
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun defaultTradingViewSource(symbols: List<String>): MarketSource = TradingViewMarketSource.connect()
    }
}
