package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExperimentPlanLoaderSpellingsTest {
    @TempDir
    lateinit var tmp: Path

    private val splits =
        "splits:\n  train: 2026-06-04T00:00:00Z/2026-06-04T04:00:00Z\n" +
            "  validation: 2026-06-04T04:00:00Z/2026-06-04T08:00:00Z\n" +
            "  test: 2026-06-04T08:00:00Z/2026-06-04T12:00:00Z\n"

    private fun load(yaml: String) =
        ExperimentPlanLoader.load(
            tmp.resolve("plan.yaml").also {
                Files.writeString(it, yaml)
            },
        )

    @Test
    fun `camel-case keys a comma list of metrics and the short grid name are accepted`() {
        val plan =
            load(
                "name: p\nprimaryMetric: sharpe\nsecondaryMetrics: calmar, totalPnL\n$splits" +
                    "grid:\n  fast: 2, 3, 3\n  slow: [9]\nselection:\n  topN: 2\n  largeSearchThreshold: 5\n",
            )

        assertThat(plan.primaryMetric).isEqualTo("sharpe")
        assertThat(plan.secondaryMetrics).containsExactly("calmar", "totalPnL")
        assertThat(plan.parameterGrid).isEqualTo(mapOf("fast" to listOf("2", "3"), "slow" to listOf("9")))
        assertThat(plan.selection.topN).isEqualTo(2)
        assertThat(plan.selection.largeSearchThreshold).isEqualTo(5)
    }

    @Test
    fun `the param_grid spelling is read and selection defaults apply`() {
        val plan =
            load(
                "name: p\nprimary_metric: sharpe\n$splits" + "param_grid:\n  fast: [2]\nselection:\n  method: ''\n",
            )

        assertThat(plan.parameterGrid).containsEntry("fast", listOf("2"))
        assertThat(plan.selection.method).isEqualTo("validation_rank_then_test_once")
        assertThat(plan.selection.largeSearchThreshold).isEqualTo(ResearchGovernance.DEFAULT_LARGE_SEARCH_THRESHOLD)
    }

    @Test
    fun `a plan that is not a mapping or lacks a metric or a split window is refused`() {
        assertThatThrownBy { load("- a list\n") }.hasMessage("plan must be a YAML mapping")
        assertThatThrownBy { load("name: p\n$splits") }.hasMessage("missing required field: primary_metric")
        assertThatThrownBy { load("name: p\nprimary_metric: sharpe\nsplits:\n  train: ''\n") }
            .hasMessage("missing required field: splits.train")
    }
}
