package com.qkt.connector.gateway

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * Drives a wire data class through every field: a full document decodes, re-encodes to itself, equals a twin and
 * differs from every single-field mutation; an optional field may be absent or null, a required one may not.
 */
internal class WireRoundTrip<T : Any>(
    private val serializer: KSerializer<T>,
    private val full: String,
    private val optional: Set<String> = emptySet(),
) {
    private val json = Json { encodeDefaults = true }
    private val root: JsonObject = json.parseToJsonElement(full).jsonObject

    private fun decode(element: JsonElement): T = json.decodeFromJsonElement(serializer, element)

    fun verify() {
        val value = decode(root)
        assertThat(json.encodeToJsonElement(serializer, value)).isEqualTo(root)
        val twin = decode(root)
        assertThat(value).isEqualTo(twin).hasSameHashCodeAs(twin)
        assertThat(value.toString()).startsWith(value.javaClass.simpleName + "(")
        assertThat(value).isNotEqualTo(Any()).isNotEqualTo(null)
        for (variant in mutations(root)) {
            val other = decode(variant)
            assertThat(other).isNotEqualTo(value)
        }
        for (key in root.keys) verifyKey(key, value)
    }

    private fun verifyKey(
        key: String,
        value: T,
    ) {
        val without = JsonObject(root - key)
        if (key in optional) {
            val absent = decode(without)
            assertThat(absent).isNotEqualTo(value)
            assertThat(absent).isEqualTo(decode(without)).hasSameHashCodeAs(decode(without))
            val explicitNull = decode(JsonObject(root + (key to JsonNull)))
            assertThat(explicitNull).isNotEqualTo(value)
        } else {
            assertThatThrownBy { decode(without) }.isInstanceOf(SerializationException::class.java)
        }
    }

    private fun mutations(element: JsonElement): List<JsonElement> =
        when (element) {
            is JsonObject ->
                element.keys.flatMap { key ->
                    mutations(element.getValue(key)).map { JsonObject(element + (key to it)) }
                }
            is JsonArray ->
                listOf<JsonElement>(JsonArray(emptyList())) +
                    element.indices.flatMap { i ->
                        mutations(element[i]).map { JsonArray(element.toMutableList().also { l -> l[i] = it }) }
                    }
            is JsonNull -> emptyList()
            is JsonPrimitive -> listOf(mutatePrimitive(element))
        }

    private fun mutatePrimitive(p: JsonPrimitive): JsonPrimitive =
        when {
            p.isString -> JsonPrimitive(p.content + "x")
            p.content == "true" || p.content == "false" -> JsonPrimitive(!p.boolean)
            else -> JsonPrimitive(p.content.toLong() + 1)
        }
}
