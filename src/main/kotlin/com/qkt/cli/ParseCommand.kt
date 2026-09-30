package com.qkt.cli

import com.qkt.cli.bot.jsonArr
import com.qkt.cli.bot.jsonObj
import com.qkt.cli.bot.jsonString
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CompileError
import com.qkt.dsl.compile.CompileErrorLocator
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
        val text = Files.readString(path)
        return when (val result = Dsl.parseAny(text)) {
            is ParseResult.Success -> compile(file, text, path, result.value, json)
            is ParseResult.Failure -> {
                for (e in result.errors) {
                    System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                }
                System.err.println("${result.errors.size} error${if (result.errors.size != 1) "s" else ""}")
                ExitCodes.USER_ERROR
            }
        }
    }

    /** Compiles a parsed file; a failure prints one located error, the same one the editor shows. */
    private fun compile(
        file: String,
        text: String,
        path: Path,
        parsed: ParsedFile,
        json: Boolean,
    ): Int {
        val kind =
            when (parsed) {
                is ParsedFile.StrategyFile -> "strategy"
                is ParsedFile.PortfolioFile -> "portfolio"
            }
        try {
            when (parsed) {
                is ParsedFile.StrategyFile -> AstCompiler().compile(parsed.ast)
                is ParsedFile.PortfolioFile -> PortfolioLoader.load(path)
            }
        } catch (e: Exception) {
            val error = CompileError.of(e)
            val at = CompileErrorLocator.locate(text, error)
            System.err.println("$file:${at.line}:${at.col} — ${error.message}")
            System.err.println("1 error")
            return ExitCodes.USER_ERROR
        }
        val (name, version, streams) =
            when (parsed) {
                is ParsedFile.StrategyFile -> Triple(parsed.ast.name, parsed.ast.version, parsed.ast.streams)
                is ParsedFile.PortfolioFile -> Triple(parsed.ast.name, parsed.ast.version, parsed.ast.streams)
            }
        println(if (json) describe(kind, name, version, streams) else "ok")
        return ExitCodes.SUCCESS
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
