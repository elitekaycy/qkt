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
    fun parse(source: String): ParseResult<StrategyAst> = expandHub(Parser(Lexer(source).tokenize()).parseStrategy())

    /**
     * A hub dataset alias is expanded into one stream per referenced field HERE, at the parse
     * boundary, so no consumer of an AST ever sees a dataset-level stream. The compiler applies
     * the same pass (idempotently) for callers that build ASTs by hand; doing it here as well is
     * what keeps every command-line path -- symbol lists, provisioning, preflight, research --
     * agreeing with the compiler about which streams a strategy actually reads. The rewrite is
     * purely syntactic, like the FOR EACH expansion the parser already performs.
     */
    private fun expandHub(result: ParseResult<StrategyAst>): ParseResult<StrategyAst> =
        when (result) {
            is ParseResult.Success ->
                ParseResult.Success(
                    com.qkt.dsl.compile.HubFieldExpansion
                        .apply(result.value)
                        .ast,
                )
            is ParseResult.Failure -> result
        }

    /** Reads the file at [path] and parses it as a strategy. */
    fun parseFile(path: Path): ParseResult<StrategyAst> = parse(Files.readString(path))

    /** Parses [source] as either a strategy or a portfolio, returning the discriminated [ParsedFile]. */
    fun parseAny(source: String): ParseResult<ParsedFile> =
        when (val result = Parser(Lexer(source).tokenize()).parseFile()) {
            is ParseResult.Success ->
                when (val file = result.value) {
                    is ParsedFile.StrategyFile ->
                        ParseResult.Success(
                            ParsedFile.StrategyFile(
                                com.qkt.dsl.compile.HubFieldExpansion
                                    .apply(file.ast)
                                    .ast,
                            ),
                        )
                    else -> result
                }
            is ParseResult.Failure -> result
        }

    /** Reads the file at [path] and parses it as either a strategy or a portfolio. */
    fun parseFileAny(path: Path): ParseResult<ParsedFile> = parseAny(Files.readString(path))
}
