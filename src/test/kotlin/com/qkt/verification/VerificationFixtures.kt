package com.qkt.verification

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One input series the oracle ran on. `bars` are `[startMs, open, high, low, close, volume]` as decimal
 * strings, so qkt sees exactly the digits the oracle saw.
 */
@Serializable
data class OracleSeries(
    val id: String,
    val source: String,
    val timeframeMs: Long,
    val bars: List<List<String>>,
)

/** `|expected - actual| <= abs + rel * |expected|`; both zero means bit-for-bit equality of the doubles. */
@Serializable
data class Tolerance(
    val abs: Double,
    val rel: Double,
) {
    fun admits(
        expected: Double,
        actual: Double,
    ): Boolean = abs(expected - actual) <= abs + rel * abs(expected)
}

/** Expected per-bar output of one registered indicator over one series; `null` is Undefined. */
@Serializable
data class IndicatorFixture(
    val indicator: String,
    val args: List<String>,
    val series: String,
    val input: String,
    val oracle: String,
    val tolerance: Tolerance,
    val note: String = "",
    val values: List<Double?>,
)

@Serializable
data class FunctionCase(
    val args: List<String>,
    val expected: String?,
)

/** Expected results of one FuncRegistry function over its argument grid; `null` is a domain error. */
@Serializable
data class FunctionFixture(
    val function: String,
    val oracle: String,
    val tolerance: Tolerance,
    val cases: List<FunctionCase>,
)

/**
 * Loads the checked-in oracle output under `verification/oracle/indicators` (regenerated only by
 * `scripts/verification-fixtures.sh`; never by running qkt). The root can be moved with the
 * `qkt.verification.oracle` system property.
 */
object VerificationFixtures {
    val root: Path = Path.of(System.getProperty("qkt.verification.oracle", "verification/oracle/indicators"))
    private val json = Json { ignoreUnknownKeys = true }

    fun series(): Map<String, OracleSeries> =
        jsonFiles(root.resolve("series"))
            .map { json.decodeFromString<OracleSeries>(it.readText()) }
            .associateBy { it.id }

    /** Fixture file stem (`INDICATOR__args__series`) to fixture, sorted by name. */
    fun indicatorFixtures(): List<Pair<String, IndicatorFixture>> =
        jsonFiles(root.resolve("fixtures/indicators"))
            .map { it.nameWithoutExtension to json.decodeFromString<IndicatorFixture>(it.readText()) }

    fun functionFixtures(): List<FunctionFixture> =
        jsonFiles(root.resolve("fixtures/functions"))
            .map { json.decodeFromString<FunctionFixture>(it.readText()) }

    private fun jsonFiles(dir: Path): List<Path> =
        dir
            .listDirectoryEntries("*.json")
            .sortedBy { it.fileName.toString() }
}
