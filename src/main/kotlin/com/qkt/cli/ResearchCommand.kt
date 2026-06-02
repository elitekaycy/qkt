package com.qkt.cli

import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.research.ReplayRepl
import com.qkt.research.ReplaySession
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** `qkt research <strategy.qkt> --from <d> --to <d>` — interactive playback REPL (#81). */
class ResearchCommand(
    private val args: Args,
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

        val from = parseInstant(args.requireOption("from"))
        val to = parseInstant(args.requireOption("to"))
        val dataRoot = args.option("data-root") ?: "./data"
        val startingBalance = args.option("starting-balance")?.let(::BigDecimal) ?: BigDecimal("10000")
        val symbols = ast.streams.map { it.symbol }.distinct()

        val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = null)
        val source = LocalMarketSource(store, FixedClock(time = to.toEpochMilli()), barStore = LocalBarStore())
        val range = TimeRange(from, to)
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
            System.err.println("qkt: error: no ticks for $symbols in [$from, $to] under $dataRoot")
            return ExitCodes.USER_ERROR
        }

        val session =
            ReplaySession(
                ticks = ticks,
                strategyPath = path,
                startingBalance = startingBalance,
                instruments = NoopInstrumentRegistry,
            )
        println("loaded ${ticks.size} ticks for $symbols  [$from .. $to]")
        ReplayRepl(session).run(BufferedReader(InputStreamReader(System.`in`)), System.out)
        return ExitCodes.SUCCESS
    }

    private fun parseInstant(s: String): Instant =
        if (s.contains('T')) {
            Instant.parse(if (s.endsWith("Z")) s else "${s}Z")
        } else {
            LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
}
