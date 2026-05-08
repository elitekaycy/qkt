package com.qkt.cli

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.nio.file.Files
import java.nio.file.Path

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
        return when (val result = Dsl.parseFile(path)) {
            is ParseResult.Success -> {
                println("ok")
                ExitCodes.SUCCESS
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
