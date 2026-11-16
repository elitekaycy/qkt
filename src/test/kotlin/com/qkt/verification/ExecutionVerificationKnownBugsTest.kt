package com.qkt.verification

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Execution scenarios where qkt disagrees with its own docs. Each is `@Disabled` with the bug it exposes and keeps
 * asserting the documented behaviour through [ExecutionVerificationTest.verify], so re-enabling it is the proof
 * once the fix lands in its own PR. [KNOWN_BUGS] removes them from the main factory.
 */
class ExecutionVerificationKnownBugsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    @Disabled(
        "bug: BUY ... OCO { STOP AT, LIMIT AT } places no entry; qkt submits a StandaloneOCO of the two exit " +
            "prices as opening orders (legIntent Open), so the stop leg opens a short (actions.md:127-136)",
    )
    fun `a long entry with an OCO exit pair is protected by the pair, not replaced by it`() = run(OCO_LONG)

    @Test
    @Disabled(
        "bug: SELL ... OCO { STOP AT, LIMIT AT } places no entry; the limit leg opens a long instead of closing " +
            "the short (actions.md:127-136)",
    )
    fun `a short entry with an OCO exit pair is protected by the pair, not replaced by it`() = run(OCO_SHORT)

    private fun run(name: String) {
        val scenario = ExecutionScenarios.scenarios().single { it.name == name }
        ExecutionVerificationTest.verify(scenario, Files.createDirectories(tmp.resolve(name)))
    }

    companion object {
        private const val OCO_LONG = "long-market-oco-stop-ticks-paper"
        private const val OCO_SHORT = "short-market-oco-target-ticks-paper"

        /** Scenario names excluded from [ExecutionVerificationTest]; each has a `@Disabled` test here. */
        val KNOWN_BUGS: Set<String> = setOf(OCO_LONG, OCO_SHORT)
    }
}
