package com.qkt.dsl.parse

/** A single parse error with 1-based [line] + [col] coordinates pointing into the source. */
data class ParseError(
    val line: Int,
    val col: Int,
    val message: String,
)

internal class ParseException(
    val error: ParseError,
) : RuntimeException(error.message)
