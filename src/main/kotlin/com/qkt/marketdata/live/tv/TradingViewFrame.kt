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

    /**
     * Session metadata frame the venue pushes once on connect — a JSON object with
     * `session_id` / `release` / `protocol` and no `m` method field. Carries no
     * actionable payload for the engine; consumers ignore it silently.
     *
     * See #189. Before this variant existed, the frame matched neither the
     * heartbeat regex nor the `m`-field message shape and was thrown as a
     * [TradingViewProtocolException].
     */
    data class SessionMeta(
        val obj: JsonObject,
    ) : TradingViewFrame()

    companion object {
        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
            }

        // Match both heartbeat shapes the venue emits:
        //   * the documented `~h~N~h~` form (echoed by clients on reply)
        //   * the bare `~h~N` form the live ws actually sends (no trailing marker)
        // See #189 — the trailing marker was previously required and live heartbeats
        // therefore never matched, surfacing as protocol exceptions on every ping.
        private val HEARTBEAT_REGEX = Regex("^~h~(\\d+)(?:~h~)?$")

        // Keys whose presence in an m-less JSON object identifies a session meta frame.
        // Any one of these is sufficient — TV doesn't always emit all three.
        private val SESSION_META_KEYS = setOf("session_id", "release", "protocol")

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
            val obj =
                tree as? JsonObject
                    ?: throw TradingViewProtocolException("Frame is not a JSON object: $payload")
            val method = obj["m"]?.jsonPrimitive?.content
            if (method == null) {
                if (obj.keys.any { it in SESSION_META_KEYS }) return SessionMeta(obj)
                throw TradingViewProtocolException("Frame missing 'm' field: $payload")
            }
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
