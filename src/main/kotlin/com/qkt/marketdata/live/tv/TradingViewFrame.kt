package com.qkt.marketdata.live.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TradingViewProtocolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

sealed class TradingViewFrame {
    data class Message(
        val method: String,
        val params: JsonArray,
    ) : TradingViewFrame() {
        fun paramAt(index: Int): JsonElement = params[index]

        fun paramAsString(index: Int): String = params[index].jsonPrimitive.content

        fun paramAsObject(index: Int): JsonObject = params[index].jsonObject

        fun toWireJson(): String {
            val obj =
                buildJsonObject {
                    put("m", JsonPrimitive(method))
                    put("p", params)
                }
            return JSON.encodeToString(JsonElement.serializer(), obj)
        }
    }

    data class Heartbeat(
        val seq: Int,
    ) : TradingViewFrame() {
        fun toWireString(): String = "~h~$seq~h~"
    }

    companion object {
        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
            }
        private val HEARTBEAT_REGEX = Regex("^~h~(\\d+)~h~$")

        fun parse(payload: String): TradingViewFrame {
            HEARTBEAT_REGEX.matchEntire(payload)?.let { m ->
                return Heartbeat(m.groupValues[1].toInt())
            }
            val tree =
                try {
                    JSON.parseToJsonElement(payload)
                } catch (e: Exception) {
                    throw TradingViewProtocolException("Cannot parse frame: $payload", e)
                }
            val obj = tree.jsonObject
            val method =
                obj["m"]?.jsonPrimitive?.content
                    ?: throw TradingViewProtocolException("Frame missing 'm' field: $payload")
            val params = obj["p"]?.jsonArray ?: JsonArray(emptyList())
            return Message(method, params)
        }
    }
}

object TradingViewFraming {
    private val WRAPPER = Regex("~m~(\\d+)~m~")

    fun encode(payload: String): String = "~m~${payload.toByteArray(Charsets.UTF_8).size}~m~$payload"

    fun decodeAll(buffer: String): DecodeResult {
        val frames = mutableListOf<String>()
        var offset = 0
        while (offset < buffer.length) {
            val match = WRAPPER.find(buffer, offset) ?: break
            if (match.range.first != offset) {
                throw TradingViewProtocolException("Unexpected bytes before frame header at offset=$offset")
            }
            val len = match.groupValues[1].toInt()
            val start = match.range.last + 1
            val end = start + len
            if (end > buffer.length) {
                return DecodeResult(frames = frames, leftover = buffer.substring(offset))
            }
            frames.add(buffer.substring(start, end))
            offset = end
        }
        return DecodeResult(frames = frames, leftover = buffer.substring(offset))
    }

    data class DecodeResult(
        val frames: List<String>,
        val leftover: String,
    )
}
