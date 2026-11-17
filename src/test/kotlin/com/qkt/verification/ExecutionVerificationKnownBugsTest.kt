package com.qkt.verification

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/**
 * Execution scenarios where qkt disagrees with its own docs. Each is held here as a `@Disabled` test with the bug it
 * exposes, asserting the documented behaviour through [ExecutionVerificationTest.verify], so re-enabling it is the
 * proof once the fix lands in its own PR. [KNOWN_BUGS] removes whole scenarios from the main factory; a scenario whose
 * fills agree but whose reported entry levels do not ([levelsUnreported]) keeps running there without the levels.
 */
class ExecutionVerificationKnownBugsTest {
    @TempDir
    lateinit var tmp: Path

    @TestFactory
    @Disabled(
        "bug #1747: trades.csv leaves stopLossPrice, takeProfitPrice and riskUsd empty on STACK entries, whose " +
            "layers each carry the bracket (backtest-model.md:263; stack.md:119-135)",
    )
    fun `every STACK entry row reports the levels its layer's bracket armed`(): List<DynamicTest> =
        ExecutionScenarios.scenarios().filter(::levelsUnreported).map { scenario ->
            dynamicTest(scenario.name) {
                ExecutionVerificationTest.verify(scenario, Files.createDirectories(tmp.resolve(scenario.name)))
            }
        }

    companion object {
        /** Scenario names excluded from [ExecutionVerificationTest]; each has a `@Disabled` test here. */
        val KNOWN_BUGS: Set<String> = emptySet()

        /** STACK scenarios: their entry rows carry no stop or target in `trades.csv` (#1747). */
        fun levelsUnreported(scenario: ExecutionScenario): Boolean = scenario.dimensions["feature"] == "stack"
    }
}
