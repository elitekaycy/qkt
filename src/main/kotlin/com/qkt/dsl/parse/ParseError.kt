package com.qkt.dsl.parse

data class ParseError(
    val line: Int,
    val col: Int,
    val message: String,
)

internal class ParseException(
    val error: ParseError,
) : RuntimeException(error.message)
