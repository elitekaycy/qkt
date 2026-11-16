package com.qkt.verification

import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The oracle set is the gate: every registered indicator and every numeric function must have fixtures, on
 * real data and on the synthetic edge series, or the build fails naming what is missing. A fixture naming
 * an unregistered indicator fails too, so a rename cannot leave a stale expectation behind.
 */
class IndicatorVerificationCompletenessTest {
    private val fixtures = VerificationFixtures.indicatorFixtures().map { it.second }
    private val series = VerificationFixtures.series()
    private val realSeries = series.keys.filterNot { it.startsWith("synth_") }.toSet()
    private val syntheticSeries = series.keys.filter { it.startsWith("synth_") }.toSet()

    @Test
    fun `every registered indicator has an oracle fixture on every real series`() {
        assertThat(realSeries).hasSize(3)
        val missing =
            IndicatorRegistry.names().flatMap { name ->
                val covered = fixtures.filter { it.indicator == name }.map { it.series }.toSet()
                (realSeries - covered).map { "$name on $it" }
            }
        assertThat(missing).describedAs("indicators without a real-data oracle fixture").isEmpty()
    }

    @Test
    fun `every registered indicator has an oracle fixture on every synthetic edge series`() {
        assertThat(syntheticSeries).containsExactlyInAnyOrder(
            "synth_flat",
            "synth_gap",
            "synth_zero",
            "synth_huge",
            "synth_tiny",
            "synth_single",
            "synth_short",
        )
        val missing =
            IndicatorRegistry.names().flatMap { name ->
                val covered = fixtures.filter { it.indicator == name }.map { it.series }.toSet()
                (syntheticSeries - covered).map { "$name on $it" }
            }
        assertThat(missing).describedAs("indicators without an edge-series oracle fixture").isEmpty()
    }

    @Test
    fun `every registered numeric function has an oracle fixture with cases`() {
        val byName = VerificationFixtures.functionFixtures().associateBy { it.function }
        val missing = FuncRegistry.names().filter { byName[it]?.cases.isNullOrEmpty() }
        assertThat(missing).describedAs("functions without an oracle fixture").isEmpty()
        assertThat(byName.keys - FuncRegistry.names()).describedAs("fixtures for unknown functions").isEmpty()
    }

    @Test
    fun `every fixture names a registered indicator and a known series`() {
        assertThat(fixtures.map { it.indicator }.filterNot { IndicatorRegistry.has(it) }.toSet())
            .describedAs("fixtures for unregistered indicators")
            .isEmpty()
        assertThat(fixtures.map { it.series }.filterNot { it in series }.toSet())
            .describedAs("fixtures over unknown series")
            .isEmpty()
        assertThat(fixtures.filter { it.values.size != series.getValue(it.series).bars.size })
            .describedAs("fixtures whose length differs from their series")
            .isEmpty()
    }

    @Test
    fun `every known-bug exclusion still names an existing fixture`() {
        val names = VerificationFixtures.indicatorFixtures().map { it.first }.toSet()
        assertThat(IndicatorVerificationKnownBugsTest.KNOWN_BUGS - names)
            .describedAs("known-bug entries without a fixture")
            .isEmpty()
    }
}
