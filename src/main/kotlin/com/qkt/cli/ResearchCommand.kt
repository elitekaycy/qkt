package com.qkt.cli

import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.DataFetcher
import com.qkt.research.ReplayRepl
import com.qkt.research.ReplaySession
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/** `qkt research <strategy.qkt> --from <d> --to <d>` — interactive playback REPL (#81). */
class ResearchCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
    private val input: InputStream = System.`in`,
    private val output: PrintStream = System.out,
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
                    return ExitCodes.USER_ERROR
                }
            }

        val startingBalance = args.option("starting-balance")?.let(::BigDecimal) ?: BigDecimal("10000")

        val ctx =
            try {
                BacktestContext.build(args, ast, fetcherOverride)
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        try {
            ctx.provision()
        } catch (e: com.qkt.backtest.IncompleteDataException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }

        // Bare stream symbols match the tick store's keying; LocalMarketSource bridges the broker prefix.
        val symbols = ast.streams.map { it.symbol }.distinct()
        val source = LocalMarketSource(ctx.store, FixedClock(time = ctx.to.toEpochMilli()), barStore = ctx.barStore)
        val range = TimeRange(ctx.from, ctx.to)
        val perSymbolFeeds = symbols.map { SequenceTickFeed(source.ticks(it, range)) }
        val feed = if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
        val ticks =
            buildList {
                feed.use { f ->
                    while (true) {
                        val t = f.next() ?: break
                        add(t)
                    }
                }
            }
        if (ticks.isEmpty()) {
            System.err.println(
                "qkt: error: no ticks for $symbols in [${ctx.from}, ${ctx.to}] under ${args.option(
                    "data-root",
                ) ?: "./data"}",
            )
            return ExitCodes.USER_ERROR
        }

        val session =
            ReplaySession(
                ticks = ticks,
                strategyPath = path,
                startingBalance = startingBalance,
                instruments = ctx.instruments,
            )
        output.println("loaded ${ticks.size} ticks for $symbols  [${ctx.from} .. ${ctx.to}]")
        ReplayRepl(session).run(BufferedReader(InputStreamReader(input)), output)
        return ExitCodes.SUCCESS
    }
}
