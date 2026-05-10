package com.qkt.cli.observe

import kotlinx.serialization.json.JsonObject

/** One entry in [EventRing] — a JSON-serializable strategy event with timestamp + kind. */
data class EventEntry(
    val ts: Long,
    val kind: String,
    val payload: JsonObject,
)
