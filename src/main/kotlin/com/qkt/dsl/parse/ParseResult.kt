package com.qkt.dsl.parse

sealed interface ParseResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ParseResult<T>

    data class Failure<T>(
        val errors: List<ParseError>,
    ) : ParseResult<T>
}
