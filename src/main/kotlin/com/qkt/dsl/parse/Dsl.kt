package com.qkt.dsl.parse

import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.StrategyAst
import java.nio.file.Files
import java.nio.file.Path

/** Discriminates between `STRATEGY` and `PORTFOLIO` files parsed by [Dsl.parseAny]. */
sealed interface ParsedFile {
    /** A single-strategy file (`STRATEGY name VERSION n ...`). */
    data class StrategyFile(
        val ast: StrategyAst,
    ) : ParsedFile

    /** A portfolio composition file (`PORTFOLIO name VERSION n ...`). */
    data class PortfolioFile(
        val ast: PortfolioAst,
    ) : ParsedFile
}

/**
 * Entry point for parsing `.qkt` DSL files.
 *
 * Returns a [ParseResult] with either the parsed AST or a list of [ParseError]s — the
 * parser collects every error it can before bailing, so users get a complete picture
 * of what's wrong in one pass.
 */
object Dsl {
    /** Parses [source] as a strategy. Use [parseAny] when the caller doesn't know the file kind. */
    fun parse(source: String): ParseResult<StrategyAst> = Parser(Lexer(source).tokenize()).parseStrategy()

    /** Reads the file at [path] and parses it as a strategy. */
    fun parseFile(path: Path): ParseResult<StrategyAst> = parse(Files.readString(path))

    /** Parses [source] as either a strategy or a portfolio, returning the discriminated [ParsedFile]. */
    fun parseAny(source: String): ParseResult<ParsedFile> = Parser(Lexer(source).tokenize()).parseFile()

    /** Reads the file at [path] and parses it as either a strategy or a portfolio. */
    fun parseFileAny(path: Path): ParseResult<ParsedFile> = parseAny(Files.readString(path))
}
