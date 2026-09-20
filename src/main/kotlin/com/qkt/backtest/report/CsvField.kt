package com.qkt.backtest.report

/**
 * Quotes [value] as one RFC 4180 CSV field: wrapped in double quotes, with inner quotes doubled,
 * when it contains a comma, quote or line break; returned unchanged otherwise.
 */
internal fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
