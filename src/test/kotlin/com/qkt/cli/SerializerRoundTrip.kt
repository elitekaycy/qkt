package com.qkt.cli

import com.qkt.cli.shim.assertEquals
import kotlinx.serialization.json.Json

/** The Json configurations the generated serializers branch on. */
internal val serializerJsonConfigs: List<Json> =
    listOf(
        Json,
        Json { encodeDefaults = true },
        Json { encodeDefaults = false },
        Json { explicitNulls = false },
        Json {
            encodeDefaults = true
            explicitNulls = false
        },
    )

/** Asserts [value] survives encode then decode under every configuration. */
internal inline fun <reified T> assertRoundTrips(value: T) {
    for (json in serializerJsonConfigs) {
        val text = json.encodeToString(kotlinx.serialization.serializer<T>(), value)
        assertEquals(value, json.decodeFromString(kotlinx.serialization.serializer<T>(), text), text)
    }
}
