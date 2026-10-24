package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.cli.bot.jsonArr
import com.qkt.cli.bot.jsonObj
import com.qkt.cli.bot.jsonString
import com.qkt.common.SessionOverlap
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * `qkt calendars <file.qkt> [--venue <name>] [--config <path>] [--instruments <path>] [--json]` — each traded
 * symbol's trading calendar and the step of [CalendarResolver] that chose it, then the samples a year qkt annualizes
 * by at the run's timeframe: the run's Sharpe waits for every symbol's bar (the overlap of their sessions), the book
 * samples on any symbol's bar (the union). Live and a backtest of the file resolve the same calendars when the
 * stream prefixes name a configured broker, or when the backtest passes the broker as `--venue`.
 */
class CalendarsCommand(
    private val args: Args,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        val streams =
            try {
                streamsOf(path)
            } catch (e: Exception) {
                System.err.println("qkt: error: $file: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        if (streams.isEmpty()) {
            System.err.println("qkt: error: $file declares no streams")
            return ExitCodes.USER_ERROR
        }
        return try {
            print(streams)
            ExitCodes.SUCCESS
        } catch (e: BacktestContext.Companion.SetupError) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.USER_ERROR
        }
    }

    private fun print(streams: List<StreamDecl>) {
        val cfg = Config.load(Config.resolvePath(args.option("config")))
        val symbols = streams.map { it.qktSymbol }.distinct()
        val instruments = InstrumentFiles.registry(Path.of(cfg.dataRoot), args.option("instruments")?.let(Path::of))
        val resolver = cfg.backtestCalendars(args.option("venue"), instruments)
        val choices = symbols.map(resolver::choose)
        val window = TimeWindow.parse(streams.first().timeframe)
        val calendar = strategyCalendar(symbols, instruments, resolver::calendarFor)
        val runSamples = calendar.periodsPerYear(calendar.runSymbols, window, SessionOverlap.ALL)
        val bookSamples = calendar.periodsPerYear(calendar.runSymbols, window, SessionOverlap.ANY)
        if (args.flag("json")) {
            val entries =
                choices.map { jsonObj("symbol" to it.qktSymbol, "calendar" to it.calendar.name, "source" to it.source) }
            println(
                "{\"schema\":\"qkt-calendars-v1\",\"symbols\":${jsonArr(entries)}," +
                    "\"timeframe\":${jsonString(window.canonicalSpec())}," +
                    "\"runSamplesPerYear\":${runSamples.toPlainString()}," +
                    "\"bookSamplesPerYear\":${bookSamples.toPlainString()}}",
            )
            return
        }
        val symbolWidth = maxOf(6, choices.maxOf { it.qktSymbol.length })
        val calendarWidth = maxOf(8, choices.maxOf { it.calendar.name.length })
        println("${"symbol".padEnd(symbolWidth)}  ${"calendar".padEnd(calendarWidth)}  source")
        for (c in choices) {
            println(
                "${c.qktSymbol.padEnd(symbolWidth)}  ${c.calendar.name.padEnd(calendarWidth)}  ${c.source}",
            )
        }
        println()
        println("samples a year at ${window.canonicalSpec()}:")
        println("  run sharpe (every symbol's bar closed)  ${rounded(runSamples)}")
        println("  book risk  (any symbol's bar closed)    ${rounded(bookSamples)}")
    }

    private fun rounded(v: java.math.BigDecimal): String =
        v.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()

    /** The traded streams: a strategy's own, or every child's plus the portfolio's for a portfolio file. */
    private fun streamsOf(path: Path): List<StreamDecl> =
        when (val parsed = Dsl.parseFileAny(path)) {
            is ParseResult.Failure -> error(parsed.errors.joinToString { "${it.line}:${it.col} ${it.message}" })
            is ParseResult.Success ->
                when (val file = parsed.value) {
                    is ParsedFile.StrategyFile -> file.ast.streams
                    is ParsedFile.PortfolioFile -> {
                        val compiled = PortfolioLoader.load(path)
                        compiled.children.flatMap { it.ast.streams } + compiled.ast.streams
                    }
                }
        }
}
