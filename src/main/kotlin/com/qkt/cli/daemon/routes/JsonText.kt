package com.qkt.cli.daemon.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Shared parser/encoder for request bodies and strategy status payloads. */
internal val routeJson = Json { encodeDefaults = true }

/** A decimal as a quoted plain string, or `null`. */
internal fun jsonDecimal(value: java.math.BigDecimal?): String = value?.let { "\"${it.toPlainString()}\"" } ?: "null"

/** A JSON array of JSON-escaped strings. */
internal fun jsonArray(items: List<String>): String = items.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

/** A JSON-escaped string, or `null`. */
internal fun jsonStringOrNull(value: String?): String = value?.let(::jsonString) ?: "null"

/** A JSON-escaped string literal. */
internal fun jsonString(value: String): String = JsonPrimitive(value).toString()
