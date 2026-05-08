package com.qkt.cli.observe

import kotlinx.serialization.json.JsonObject

data class EventEntry(
    val ts: Long,
    val kind: String,
    val payload: JsonObject,
)
