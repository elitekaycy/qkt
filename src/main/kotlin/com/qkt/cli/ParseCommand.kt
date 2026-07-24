package com.qkt.cli

import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import java.nio.file.Files
import java.nio.file.Path

/** `qkt parse <file.qkt>` — parse and compile DSL check, prints errors with line:col coordinates. */
class ParseCommand(
    private val args: Args,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
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
                            println("ok")
                            ExitCodes.SUCCESS
                        } catch (e: Exception) {
                            System.err.println("$file:1:1 — ${e.message ?: e.toString()}")
                            System.err.println("1 error")
                            ExitCodes.USER_ERROR
                        }
                    is ParsedFile.PortfolioFile ->
                        try {
                            PortfolioLoader.load(path)
                            println("ok")
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
}
