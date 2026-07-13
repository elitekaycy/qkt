package com.qkt.cli.bot

import java.math.BigDecimal

/** Escapes and quotes [v] as a JSON string literal. */
internal fun jsonString(v: String): String =
    "\"" +
        v
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") +
        "\""

/** Encodes a Kotlin value as a JSON value: numbers/booleans bare, everything else quoted. */
internal fun jsonValue(v: Any?): String =
    when (v) {
        null -> "null"
        is String -> jsonString(v)
        is Boolean, is Int, is Long -> v.toString()
        is BigDecimal -> v.toPlainString()
        is List<*> -> v.joinToString(",", "[", "]") { jsonValue(it) }
        else -> jsonString(v.toString())
    }

/** Builds a JSON object from ordered field pairs; values are encoded via [jsonValue]. */
internal fun jsonObj(vararg fields: Pair<String, Any?>): String =
    fields.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}:${jsonValue(v)}" }

/** Builds a JSON array from pre-encoded element strings. */
internal fun jsonArr(elements: List<String>): String = elements.joinToString(",", "[", "]")
