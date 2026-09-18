package com.qkt.cli

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The compact JSON format used for promotion records and gate results. */
object PromotionJson {
    val format: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    fun encode(record: PromotionRecord): String = format.encodeToString(record)

    fun encode(result: PromotionGateResult): String = format.encodeToString(result)
}
