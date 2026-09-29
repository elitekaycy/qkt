package com.qkt.cli

import com.qkt.cli.bot.jsonArr
import com.qkt.cli.bot.jsonObj
import com.qkt.cli.bot.jsonString
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * `qkt parse <file.qkt> [--json]` — parse and compile DSL check, prints errors with line:col
 * coordinates. With `--json` a valid file prints its machine-readable description instead of
 * `ok`: name, version, kind, and one entry per stream with the calendar qkt applies (#1278).
 */
class ParseCommand(
    private val args: Args,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        val json = args.flag("json")
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        return when (val result = Dsl.parseFileAny(path)) {
            is ParseResult.Success -> {
                when (val parsed = result.value) {
                    is ParsedFile.StrategyFile ->
                        try {
                            AstCompiler().compile(parsed.ast)
                            println(
                                if (json) {
                                    describe(
                                        "strategy",
                                        parsed.ast.name,
                                        parsed.ast.version,
                                        parsed.ast.streams,
                                    )
                                } else {
                                    "ok"
                                },
                            )
                            ExitCodes.SUCCESS
                        } catch (e: Exception) {
                            System.err.println("$file:1:1 — ${e.message ?: e.toString()}")
                            System.err.println("1 error")
                            ExitCodes.USER_ERROR
                        }
                    is ParsedFile.PortfolioFile ->
                        try {
                            PortfolioLoader.load(path)
                            println(
                                if (json) {
                                    describe(
                                        "portfolio",
                                        parsed.ast.name,
                                        parsed.ast.version,
                                        parsed.ast.streams,
                                    )
                                } else {
                                    "ok"
                                },
                            )
                            ExitCodes.SUCCESS
                        } catch (e: Exception) {
                            System.err.println("$file:1:1 — ${e.message ?: e.toString()}")
                            System.err.println("1 error")
                            ExitCodes.USER_ERROR
                        }
                }
            }
            is ParseResult.Failure -> {
                for (e in result.errors) {
                    System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                }
                System.err.println("${result.errors.size} error${if (result.errors.size != 1) "s" else ""}")
                ExitCodes.USER_ERROR
            }
        }
    }

    private fun describe(
        kind: String,
        name: String,
        version: Int,
        streams: List<StreamDecl>,
    ): String {
        val entries =
            streams.map { s ->
                jsonObj(
                    "alias" to s.alias,
                    "venue" to s.broker,
                    "symbol" to s.symbol,
                    "qktSymbol" to s.qktSymbol,
                    "timeframe" to s.timeframe,
                    "warmupBars" to s.warmupBars,
                    "calendar" to BacktestContext.defaultCalendars().calendarFor(s.symbol).name,
                )
            }
        return "{" +
            "\"schema\":\"qkt-parse-v1\"," +
            "\"kind\":${jsonString(kind)}," +
            "\"name\":${jsonString(name)}," +
            "\"version\":$version," +
            "\"streams\":${jsonArr(entries)}" +
            "}"
    }
}
