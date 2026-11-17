package com.qkt.common

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/** Shared checks for `@Serializable` wire classes: round trips under each Json setting, refusals naming a field. */
object WireAsserts {
    val jsons: List<Json> =
        listOf(
            Json,
            Json { encodeDefaults = true },
            Json {
                encodeDefaults = true
                explicitNulls = false
            },
            Json {
                explicitNulls = false
                ignoreUnknownKeys = true
            },
        )

    inline fun <reified T> roundTrips(value: T) {
        for (j in jsons) {
            assertThat(j.decodeFromString<T>(j.encodeToString(value))).isEqualTo(value)
        }
    }

    inline fun <reified T> decodes(
        body: String,
        expected: T,
    ) {
        assertThat(Json.decodeFromString<T>(body)).isEqualTo(expected)
    }

    /** Dropping each of [required] from [fullJson] must be refused with a message naming that field. */
    inline fun <reified T> refusesWithout(
        fullJson: String,
        vararg required: String,
    ) {
        val obj = Json.parseToJsonElement(fullJson) as JsonObject
        for (key in required) {
            val body = JsonObject(obj.filterKeys { it != key }).toString()
            assertThatThrownBy { Json.decodeFromString<T>(body) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining(key)
        }
    }

    /** Replacing the value of each of [keys] with an object must be refused. */
    inline fun <reified T> refusesWrongType(
        fullJson: String,
        vararg keys: String,
    ) {
        val obj = Json.parseToJsonElement(fullJson) as JsonObject
        for (key in keys) {
            val bad = buildJsonObject { put("x", 1) }
            val body = JsonObject(obj + (key to bad)).toString()
            assertThatThrownBy { Json.decodeFromString<T>(body) }
                .isInstanceOf(SerializationException::class.java)
        }
    }
}
