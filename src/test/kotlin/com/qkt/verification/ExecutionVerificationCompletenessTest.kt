package com.qkt.verification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tranche-A and tranche-B matrices are the gate: every dimension value below must have at least one scenario,
 * every scenario must have its `.qkt` and its oracle expectation, and every expectation must belong to a scenario,
 * or the build fails naming what is missing. Adding a dimension here without a scenario fails on purpose.
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
    fun `every tranche-B dimension value has at least one scenario`() {
        val missing =
            TRANCHE_B.flatMap { (dimension, values) ->
                val covered = scenarios.mapNotNull { it.dimensions[dimension] }.toSet()
                (values - covered).map { "$dimension=$it" }
            }
        assertThat(missing).describedAs("tranche-B dimension values without a scenario").isEmpty()
        val trancheB = scenarios.filter { "feature" in it.dimensions }
        assertThat(trancheB.size).describedAs("tranche B aims at about forty scenarios").isGreaterThanOrEqualTo(40)
    }

    @Test
    fun `every tranche-B feature is verified long and short and the order-routed ones on mt5-sim`() {
        val byFeature = scenarios.filter { "feature" in it.dimensions }.groupBy { it.dimensions.getValue("feature") }
        val oneSided = setOf("trailing-exit")
        val missing =
            TRANCHE_B.getValue("feature").flatMap { feature ->
                val cases = byFeature[feature].orEmpty()
                val sides = cases.mapNotNull { it.dimensions["side"] }.toSet()
                val wantSides = if (feature in oneSided) setOf("long") else setOf("long", "short")
                val gaps = (wantSides - sides).map { "$feature side=$it" }
                val onMt5 = cases.any { it.dimensions["broker"] == "mt5-sim" }
                if (feature in ROUTED_ON_MT5 && !onMt5) gaps + "$feature mt5-sim" else gaps
            }
        assertThat(missing).describedAs("tranche-B feature x side / broker pairs without a scenario").isEmpty()
    }

    @Test
    fun `every scenario dimension value is a known one`() {
        val unknown =
            scenarios.flatMap { s ->
                s.dimensions.mapNotNull { (dimension, value) ->
                    val known = (TRANCHE_A[dimension].orEmpty() + TRANCHE_B[dimension].orEmpty()).ifEmpty { null }
                    when {
                        known == null -> "${s.name}: unknown dimension $dimension"
                        value !in known -> "${s.name}: unknown $dimension=$value"
                        else -> null
                    }
                }
            }
        assertThat(unknown).describedAs("dimension values not listed in TRANCHE_A or TRANCHE_B").isEmpty()
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

        private val ROUTED_ON_MT5 = setOf("oto", "stack", "stack-at", "exit-after", "armed-trailing", "multi-rule")

        /** Slice 4: OTO, STACK, STACK_AT, EXIT AFTER, trailing, OCO_ENTRY, collisions on one tick, rules on one bar. */
        val TRANCHE_B: Map<String, Set<String>> =
            mapOf(
                "feature" to
                    setOf(
                        "oto",
                        "stack",
                        "stack-at",
                        "exit-after",
                        "trailing-entry",
                        "trailing-exit",
                        "armed-trailing",
                        "oco-entry",
                        "multi-rule",
                    ),
                "shape" to
                    (
                        "oto-market-child oto-limit-child oto-entry-signal-estimate oto-entry-stop-price " +
                            "oto-parent-unfilled oto-child-unfilled stack-above stack-above-short stack-below " +
                            "stack-list stack-within stack-exit-cancels stack-at-mfe stack-at-two-tiers " +
                            "stack-at-expired stack-at-recoil exit-after-plain exit-after-off-tick " +
                            "exit-after-bracket-timer exit-after-bracket-first exit-after-pending " +
                            "exit-after-stack-at trailing-by trailing-pct armed-trailing-hwm armed-trailing-pre-arm " +
                            "armed-trailing-inception oco-entry-gap oco-entry-tie oco-entry-dual-breach two-market " +
                            "market-and-limit"
                    ).split(' ').toSet(),
                "collision" to
                    setOf(
                        "none",
                        "stack-layer-on-stop-tick",
                        "stack-at-tier-on-target-tick",
                        "oco-entry-both-legs-gapped",
                        "two-rules-same-bar",
                    ),
            )
    }
}
