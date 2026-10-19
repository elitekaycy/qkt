package com.qkt.marketdata.live.tv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** The TradingView connection the chart and quote sessions talk through; faked in tests. */
interface TradingViewWebSocketLike {
    fun addListener(listener: TradingViewListener)

    fun removeListener(listener: TradingViewListener)

    fun send(
        method: String,
        params: List<Any>,
    )

    fun close()
}

/** A command's parameters as the JSON array TradingView reads: strings, numbers, booleans, JSON, lists. */
internal fun tradingViewParams(params: List<Any>): JsonArray =
    buildJsonArray { params.forEach { add(toJsonElement(it)) } }

private fun toJsonElement(value: Any): JsonElement =
    when (value) {
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is JsonElement -> value
        is List<*> ->
            buildJsonArray {
                value.forEach { add(toJsonElement(it ?: error("null param element"))) }
            }
        else -> error("Unsupported parameter type: ${value::class.java.simpleName}")
    }
