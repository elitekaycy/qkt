package com.qkt.cli.golden

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val strictJson = Json { ignoreUnknownKeys = false }
private val safeSegment = Regex("[A-Za-z0-9._-]+")

/** Parses one JSON object from a golden bundle, failing with the source position when malformed. */
internal fun parseObject(
    text: String,
    source: String,
    lineNumber: Long,
): JsonObject =
    try {
        strictJson.parseToJsonElement(text).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("malformed JSON at $source:$lineNumber: ${error.message}")
    }

/** Reads a non-blank text field or fails naming the field and source position. */
internal fun requireText(
    record: JsonObject,
    field: String,
    source: String,
    lineNumber: Long,
): String =
    record[field]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("missing $field at $source:$lineNumber")

/** Reads a required integer field; absent and malformed values fail with distinct messages. */
internal fun requireLong(
    record: JsonObject,
    field: String,
    source: String,
    lineNumber: Long,
): Long =
    optionalLong(record, field, source, lineNumber)
        ?: throw IllegalArgumentException("missing numeric $field at $source:$lineNumber")

/** Reads an optional integer field; a present but malformed value still fails. */
internal fun optionalLong(
    record: JsonObject,
    field: String,
    source: String,
    lineNumber: Long,
): Long? {
    val raw = record[field]?.jsonPrimitive?.contentOrNull ?: return null
    return raw.toLongOrNull()
        ?: throw IllegalArgumentException("invalid numeric $field at $source:$lineNumber")
}

/** Reads a required decimal field; absent and malformed values fail with distinct messages. */
internal fun requireDecimal(
    record: JsonObject,
    field: String,
    source: String,
    lineNumber: Long,
): BigDecimal =
    optionalDecimal(record, field, source, lineNumber)
        ?: throw IllegalArgumentException("missing decimal $field at $source:$lineNumber")

/** Reads an optional decimal field; a present but malformed value still fails. */
internal fun optionalDecimal(
    record: JsonObject,
    field: String,
    source: String,
    lineNumber: Long,
): BigDecimal? {
    val raw = record[field]?.jsonPrimitive?.contentOrNull ?: return null
    return runCatching { BigDecimal(raw) }.getOrNull()
        ?: throw IllegalArgumentException("invalid decimal $field at $source:$lineNumber")
}

/** Reads the record's `broker:symbol` field and checks both segments are path-safe. */
internal fun requireQktSymbol(
    record: JsonObject,
    source: String,
    lineNumber: Long,
): String {
    val symbol = requireText(record, "symbol", source, lineNumber)
    splitSymbol(symbol)
    return symbol
}

/** Splits a qualified `broker:symbol` into its path-safe broker and bare-symbol segments. */
internal fun splitSymbol(symbol: String): Pair<String, String> {
    val parts = symbol.split(':', limit = 2)
    require(parts.size == 2 && parts.all { safeSegment.matches(it) }) { "unsafe or unqualified symbol: $symbol" }
    return parts[0] to parts[1]
}
