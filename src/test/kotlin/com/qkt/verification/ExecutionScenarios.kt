package com.qkt.verification

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * One execution scenario under `verification/oracle/engine/scenarios`: the tape qkt replays (ticks as
 * `[offsetSec, bid, ask]` from `t0`), the instrument it trades, the run's broker and fill mode. The strategy is the
 * sibling `.qkt`; the declarative `actions` the oracle replays are not needed here and are ignored.
 */
@Serializable
data class ExecutionScenario(
    val name: String,
    val proves: String,
    val dimensions: Map<String, String>,
    val t0: String,
    val timeframeSec: Int,
    val symbol: String,
    val instrument: Map<String, JsonPrimitive?>,
    val broker: String,
    val fillMode: String,
    val positionMode: String = "hedging",
    val startingBalance: String = "10000",
    val ticks: List<List<JsonPrimitive>>,
    val notes: List<String> = emptyList(),
)

/** One expected fill; `barStart` is set when the expectation is only bar-granular (the synthetic bar path). */
@Serializable
data class ExpectedFill(
    val ts: Long,
    val side: String,
    val qty: String,
    val price: String,
    val realized: String,
    val reason: String,
    val orderType: String,
    val barStart: Long? = null,
    val stopLoss: String? = null,
    val takeProfit: String? = null,
)

/** The oracle's expectation for one scenario (`verification/oracle/engine/expected/<name>.json`). */
@Serializable
data class ExecutionExpectation(
    val name: String,
    val proves: String,
    val oracle: String,
    val timestampGranularity: String,
    val trades: List<ExpectedFill>,
    val realized: String,
    val commission: String,
    val rejections: Int,
    val openAtEnd: Boolean,
    val notes: List<String> = emptyList(),
)

/**
 * Loads the checked-in execution scenarios and oracle expectations (regenerated only by
 * `scripts/verification-fixtures.sh`, never by running qkt). The root can be moved with the
 * `qkt.verification.engine` system property.
 */
object ExecutionScenarios {
    val root: Path = Path.of(System.getProperty("qkt.verification.engine", "verification/oracle/engine"))
    private val json = Json { ignoreUnknownKeys = true }

    fun scenarios(): List<ExecutionScenario> =
        root
            .resolve("scenarios")
            .listDirectoryEntries("*.json")
            .sortedBy { it.fileName.toString() }
            .map { json.decodeFromString<ExecutionScenario>(it.readText()) }

    fun strategy(name: String): String = root.resolve("scenarios/$name.qkt").readText()

    fun expectation(name: String): ExecutionExpectation =
        json.decodeFromString<ExecutionExpectation>(root.resolve("expected/$name.json").readText())

    fun expectationNames(): Set<String> =
        root
            .resolve("expected")
            .listDirectoryEntries("*.json")
            .map { it.fileName.toString().removeSuffix(".json") }
            .toSet()
}
