package com.qkt.dsl.parse

/** Result of a parse: either [Success] with the AST or [Failure] with collected errors. */
sealed interface ParseResult<out T> {
    /** Parse succeeded; `value` is the AST. */
    data class Success<T>(
        val value: T,
    ) : ParseResult<T>

    /** Parse failed; `errors` lists every error the parser found before bailing. */
    data class Failure<T>(
        val errors: List<ParseError>,
    ) : ParseResult<T>
}
