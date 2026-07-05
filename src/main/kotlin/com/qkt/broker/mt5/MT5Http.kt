package com.qkt.broker.mt5

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Request

/** Builds a gateway request and applies bearer authentication when configured. */
fun mt5RequestBuilder(
    url: String,
    apiKey: String?,
): Request.Builder =
    Request.Builder().url(url).apply {
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
    }

/** Accepts both the current `{data: ...}` envelope and legacy bare payloads. */
fun unwrapMT5Data(element: JsonElement): JsonElement =
    if (element is JsonObject && element["data"] != null) element.getValue("data") else element
