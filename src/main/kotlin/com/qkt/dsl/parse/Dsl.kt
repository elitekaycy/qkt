package com.qkt.dsl.parse

import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.StrategyAst
import java.nio.file.Files
import java.nio.file.Path

sealed interface ParsedFile {
    data class StrategyFile(
        val ast: StrategyAst,
    ) : ParsedFile

    data class PortfolioFile(
        val ast: PortfolioAst,
    ) : ParsedFile
}

object Dsl {
    fun parse(source: String): ParseResult<StrategyAst> = Parser(Lexer(source).tokenize()).parseStrategy()

    fun parseFile(path: Path): ParseResult<StrategyAst> = parse(Files.readString(path))

    fun parseAny(source: String): ParseResult<ParsedFile> = Parser(Lexer(source).tokenize()).parseFile()

    fun parseFileAny(path: Path): ParseResult<ParsedFile> = parseAny(Files.readString(path))
}
