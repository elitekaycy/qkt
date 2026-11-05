package com.qkt.connectivity.transport

/** [value] as a JSON string literal, escaping backslash, quote and the line-control characters. */
internal fun jsonLiteral(value: String): String =
    buildString {
        append('"')
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
