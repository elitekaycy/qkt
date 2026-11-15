package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test

class BacktestRunConfigTest {
    @Test
    fun `empty and missing blocks parse to empty sections`() {
        assertThat(BacktestRunConfig.parse(null)).isEqualTo(BacktestSection())
        assertThat(BacktestRunConfig.parse("nope")).isEqualTo(BacktestSection())
    }

    @Test
    fun `global keys split into strategy, values and params`() {
        val section =
            BacktestRunConfig.parse(
                mapOf(
                    "strategy" to "s/first.qkt",
                    "from" to "2025-09-01",
                    "no-fetch" to true,
                    "param" to mapOf("fast" to 5),
                ),
            )
        assertThat(section.strategy).isEqualTo("s/first.qkt")
        assertThat(section.defaults).containsEntry("from", "2025-09-01")
        assertThat(section.defaults).containsEntry("no-fetch", "true")
        assertThat(section.defaultParams).containsExactly("fast=5")
        assertThat(section.warnings).isEmpty()
    }

    @Test
    fun `named runs parse with inheritance left to the merger`() {
        val section =
            BacktestRunConfig.parse(
                mapOf(
                    "from" to "2025-09-01",
                    "runs" to
                        mapOf(
                            "scalps" to mapOf("strategy" to "s/scalp.qkt", "from" to "2025-10-01"),
                        ),
                ),
            )
        assertThat(section.runs.keys).containsExactly("scalps")
        assertThat(section.runs.getValue("scalps").strategy).isEqualTo("s/scalp.qkt")
        assertThat(section.runs.getValue("scalps").values).containsEntry("from", "2025-10-01")
    }

    @Test
    fun `unknown keys warn without failing`() {
        val section =
            BacktestRunConfig.parse(
                mapOf(
                    "bogus" to "x",
                    "runs" to mapOf("r" to mapOf("alsobogus" to 1, "param" to "notamap")),
                ),
            )
        // Tolerant: accepted as-is, with a warning pointing at the likely typo.
        assertThat(section.defaults).containsEntry("bogus", "x")
        assertThat(section.warnings).anyMatch { it.contains("backtest.bogus") }
        assertThat(section.warnings).anyMatch { it.contains("backtest.runs.r.alsobogus") }
    }

    @Test
    fun `non-map runs and null entries warn and skip`() {
        val section =
            BacktestRunConfig.parse(
                mapOf(
                    "runs" to mapOf("a" to "notamap", "b" to null, "c" to mapOf("strategy" to "s.qkt")),
                ),
            )
        assertThat(section.runs.keys).containsExactly("c")
        assertThat(section.warnings.size).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `merge layers named over global with provenance`() {
        val section =
            BacktestSection(
                defaults = mapOf("from" to "2025-09-01", "broker" to "paper"),
                defaultParams = listOf("fast=3"),
                strategy = "s/a.qkt",
                runs = mapOf("b" to BacktestRun(mapOf("broker" to "mt5-sim"), listOf("fast=8"), "s/b.qkt")),
            )
        val merged = BacktestRunSelection.merge(section, section.runs.getValue("b"), "b")
        assertThat(merged.values).containsEntry("from", "2025-09-01")
        assertThat(merged.values).containsEntry("broker", "mt5-sim")
        assertThat(merged.provenance).containsEntry("from", "global")
        assertThat(merged.provenance).containsEntry("broker", "run:b")
        assertThat(merged.params).containsExactly("fast=3", "fast=8")
        assertThat(merged.paramProvenance).containsEntry("fast", "run:b")
    }

    @Test
    fun `nested values where a scalar belongs are ignored with a warning naming the key`() {
        val section =
            BacktestRunConfig.parse(
                mapOf(
                    "from" to listOf("2025-09-01"),
                    "to" to null,
                    "runs" to
                        mapOf("r" to mapOf("broker" to mapOf("kind" to "paper"), "to" to null, "from" to "2025-10-01")),
                ),
            )

        assertThat(section.defaults).isEmpty()
        assertThat(section.runs.getValue("r").values).containsExactly(entry("from", "2025-10-01"))
        assertThat(section.warnings).containsExactly(
            "backtest.from: expected a scalar value, ignoring",
            "backtest.runs.r.broker: expected a scalar value, ignoring",
        )
    }

    @Test
    fun `runs that are not a map are ignored with a warning`() {
        val section = BacktestRunConfig.parse(mapOf("strategy" to "s.qkt", "runs" to listOf("a", "b")))

        assertThat(section.runs).isEmpty()
        assertThat(section.strategy).isEqualTo("s.qkt")
        assertThat(section.warnings).containsExactly("backtest.runs: expected a map of run names, ignoring")
    }

    @Test
    fun `yaml truthy spellings are true and anything else is literal`() {
        assertThat(listOf("true", " YES ", "on", "1").map(BacktestRunConfig::isTruthy)).containsOnly(true)
        assertThat(listOf("false", "", "y", "2").map(BacktestRunConfig::isTruthy)).containsOnly(false)
    }
}
