package com.qkt.observe.insights

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every envelope `type` qkt emits is one the insights collector accepts: the type literals in the sources
 * that build envelopes are checked against a pinned copy of the contract's type list
 * (`insights-contract-types.txt`, from qkt-insights `payloads.ts`). An envelope of an unknown type is
 * quarantined by the collector, so a new type ships to insights first.
 */
class InsightsContractTypesTest {
    @Test
    fun `every emitted envelope type is in the insights contract`() {
        val contract =
            requireNotNull(javaClass.getResource("/insights-contract-types.txt"))
                .readText()
                .lines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        val emitted = emittedTypes()

        assertThat(emitted).contains("state.account", "state.positions", "broker.deal", "broker.connected", "trade")
        assertThat(contract).containsAll(emitted)
    }

    /** Dotted type literals (and the bare `signal`, `trade`, `log`) in every source that builds an envelope. */
    private fun emittedTypes(): Set<String> =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .filter { text -> BUILDERS.any { it in text } }
            .flatMap { text -> LITERAL.findAll(text).map { it.groupValues[1] } }
            .filter { it.contains('.') || it in BARE }
            .filterNot { it.startsWith("com.") || it in JOURNAL_ONLY }
            .toSet()

    private companion object {
        val BUILDERS = listOf("InsightsEnvelope(", "busEnvelope(", "recordEvent(")
        val LITERAL = Regex("\"([a-z_]+(?:\\.[a-z_]+)*)\"")
        val BARE = setOf("signal", "trade", "log")

        /** `qkt bot` journal line kinds whose envelopes go out as `order.submit`/`order.accepted`/`order.rejected`. */
        val JOURNAL_ONLY = setOf("bot.submit", "bot.accepted", "bot.rejected")
    }
}
