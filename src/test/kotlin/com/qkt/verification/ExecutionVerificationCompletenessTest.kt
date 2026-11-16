package com.qkt.verification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tranche-A matrix is the gate: every dimension value below must have at least one scenario, every scenario
 * must have its `.qkt` and its oracle expectation, and every expectation must belong to a scenario, or the build
 * fails naming what is missing. Adding a dimension here without a scenario fails on purpose.
 */
class ExecutionVerificationCompletenessTest {
    private val scenarios = ExecutionScenarios.scenarios()
    private val names = scenarios.map { it.name }.toSet()

    @Test
    fun `every tranche-A dimension value has at least one scenario`() {
        val missing =
            TRANCHE_A.flatMap { (dimension, values) ->
                val covered = scenarios.mapNotNull { it.dimensions[dimension] }.toSet()
                (values - covered).map { "$dimension=$it" }
            }
        assertThat(missing).describedAs("tranche-A dimension values without a scenario").isEmpty()
    }

    @Test
    fun `every scenario dimension value is a known one`() {
        val unknown =
            scenarios.flatMap { s ->
                s.dimensions.mapNotNull { (dimension, value) ->
                    val known = TRANCHE_A[dimension]
                    when {
                        known == null -> "${s.name}: unknown dimension $dimension"
                        value !in known -> "${s.name}: unknown $dimension=$value"
                        else -> null
                    }
                }
            }
        assertThat(unknown).describedAs("dimension values not listed in TRANCHE_A").isEmpty()
    }

    @Test
    fun `every scenario has a strategy, an expectation and a unique name`() {
        assertThat(scenarios.map { it.name }).doesNotHaveDuplicates()
        assertThat(scenarios.size).describedAs("tranche A aims at about forty scenarios").isGreaterThanOrEqualTo(40)
        val expectations = ExecutionScenarios.expectationNames()
        assertThat(names - expectations).describedAs("scenarios without an expectation").isEmpty()
        assertThat(expectations - names).describedAs("expectations without a scenario").isEmpty()
        scenarios.forEach { s ->
            val header = "STRATEGY ${s.name.replace('-', '_')} VERSION 1"
            assertThat(ExecutionScenarios.strategy(s.name)).describedAs("${s.name}.qkt").contains(header)
            assertThat(ExecutionScenarios.expectation(s.name).name).isEqualTo(s.name)
        }
    }

    @Test
    fun `both sides and both fill paths are covered for the same-bar stop-and-target case`() {
        val sameBar = scenarios.filter { it.dimensions["path"] == "same-bar-both" }
        assertThat(sameBar.map { it.dimensions["side"] to it.dimensions["fills"] }.toSet())
            .containsAll(
                listOf("long" to "ticks", "short" to "ticks", "long" to "bars-synthetic", "short" to "bars-synthetic"),
            )
    }

    @Test
    fun `every known-bug exclusion still names an existing scenario`() {
        assertThat(ExecutionVerificationKnownBugsTest.KNOWN_BUGS - names)
            .describedAs("known-bug entries without a scenario")
            .isEmpty()
    }

    companion object {
        /** Slice 3 of docs/superpowers/specs/2026-11-16-dsl-verification-design.md, as dimension values. */
        val TRANCHE_A: Map<String, Set<String>> =
            mapOf(
                "entry" to setOf("market", "limit", "stop", "stop-limit"),
                "exit" to setOf("bracket-by", "bracket-pct", "bracket-at", "oco", "close-rule", "partial-close"),
                "side" to setOf("long", "short"),
                "fills" to setOf("ticks", "bars-synthetic", "bars-ticks"),
                "path" to
                    setOf(
                        "stop",
                        "target",
                        "same-bar-both",
                        "gap-stop",
                        "gap-target",
                        "entry-and-stop-same-bar",
                        "close",
                        "none",
                    ),
                "sizing" to
                    setOf(
                        "fixed",
                        "fixed-step",
                        "fixed-cs100",
                        "usd",
                        "pct-equity",
                        "pct-risk",
                        "pct-risk-step",
                        "risk-usd",
                        "risk-usd-below-min",
                    ),
                "costs" to
                    setOf(
                        "none",
                        "spread",
                        "commission",
                        "spreadPoints",
                        "spread+slippage",
                        "commission+spread+slippage",
                    ),
                "broker" to setOf("paper", "mt5-sim"),
                "mode" to setOf("hedging", "netting"),
            )
    }
}
