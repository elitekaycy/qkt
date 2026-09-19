package com.qkt.cli.golden

/** Renders [value] as a quoted JSON string literal for the hand-written golden manifests. */
internal fun jsonString(value: String): String =
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
