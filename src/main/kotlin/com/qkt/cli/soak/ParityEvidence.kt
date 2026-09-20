package com.qkt.cli.soak

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Validated parity evidence; [root] is embedded verbatim in the soak report. */
internal data class ParityEvidence(
    val durationMinutes: Long,
    val timeframes: List<String>,
    val root: JsonObject,
)

/** Requires every parity coverage counter to be positive and every mismatch counter to be zero. */
internal fun inspectParity(path: Path): ParityEvidence {
    val root = parseObject(Files.readString(path), path, 1L)

    fun positive(name: String): Long {
        val value =
            root[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: throw IllegalArgumentException("parity evidence missing $name")
        require(value > 0L) { "parity.$name must be positive" }
        return value
    }

    fun zero(name: String) {
        val value =
            root[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: throw IllegalArgumentException("parity evidence missing $name")
        require(value == 0L) { "parity.$name must be zero" }
    }
    val timeframes =
        root["timeframesTested"]
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.filter { it.isNotBlank() }
            ?: throw IllegalArgumentException("parity evidence missing timeframesTested")
    require(timeframes.isNotEmpty()) { "parity.timeframesTested must be non-empty" }
    val duration = positive("durationMinutes")
    listOf(
        "strategiesTested",
        "indicatorsTested",
        "mathScenariosTested",
        "dslScenariosTested",
        "orderTypesTested",
        "totalTicks",
        "totalBars",
        "fills",
        "parityComparisons",
        "insightsEvents",
        "warmupBars",
        "warmupTicks",
        "barBoundaryTransitions",
    ).forEach(::positive)
    listOf("parityMismatches", "unexplainedRejections", "unexplainedOrderOutcomes").forEach(::zero)
    return ParityEvidence(duration, timeframes, root)
}
