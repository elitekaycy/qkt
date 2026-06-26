package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExperimentPlanTest {
    @Test
    fun `valid plan parses dataset splits metrics constraints and grid`(
        @TempDir dir: Path,
    ) {
        val planPath = dir.resolve("plan.yaml")
        Files.writeString(
            planPath,
            """
            name: xau-breakout-v1
            objective: Evaluate XAU breakout
            strategy: s.qkt
            dataset: xau-snapshot.json
            primary_metric: totalPnL
            secondary_metrics: [calmar, profitFactor]
            splits:
              train: 2026-06-04T00:00:00Z/2026-06-04T04:00:00Z
              validation: 2026-06-04T04:00:00Z/2026-06-04T08:00:00Z
              test: 2026-06-04T08:00:00Z/2026-06-04T12:00:00Z
            constraints:
              min_trades: 10
            parameter_grid:
              fast: [2, 3]
              slow: "9, 12"
            selection:
              method: validation_rank_then_test_once
              top_n: 2
              large_search_threshold: 10
            promotion:
              state: candidate
              rationale: survived first validation pass
            seed: 42
            """.trimIndent(),
        )

        val plan = ExperimentPlanLoader.load(planPath)

        assertThat(plan.name).isEqualTo("xau-breakout-v1")
        assertThat(plan.dataset).isEqualTo("xau-snapshot.json")
        assertThat(plan.primaryMetric).isEqualTo("totalPnL")
        assertThat(plan.secondaryMetrics).containsExactly("calmar", "profitFactor")
        assertThat(plan.constraints).containsEntry("min_trades", "10")
        assertThat(plan.parameterGrid).containsEntry("fast", listOf("2", "3"))
        assertThat(plan.parameterGrid).containsEntry("slow", listOf("9", "12"))
        assertThat(plan.selection.topN).isEqualTo(2)
        assertThat(plan.promotion?.state).isEqualTo("candidate")
        assertThat(plan.seed).isEqualTo(42L)
    }

    @Test
    fun `plan rejects overlapping splits`(
        @TempDir dir: Path,
    ) {
        val planPath = dir.resolve("bad.yaml")
        Files.writeString(
            planPath,
            """
            name: bad
            primary_metric: sharpe
            splits:
              train: 2026-06-04T00:00:00Z/2026-06-04T06:00:00Z
              validation: 2026-06-04T05:00:00Z/2026-06-04T08:00:00Z
              test: 2026-06-04T08:00:00Z/2026-06-04T12:00:00Z
            parameter_grid:
              fast: [2, 3]
            """.trimIndent(),
        )

        assertThatThrownBy { ExperimentPlanLoader.load(planPath) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("overlaps")
    }

    @Test
    fun `plan rejects empty grid axes`(
        @TempDir dir: Path,
    ) {
        val planPath = dir.resolve("bad-grid.yaml")
        Files.writeString(
            planPath,
            """
            name: bad-grid
            primary_metric: sharpe
            splits:
              train: 2026-06-04T00:00:00Z/2026-06-04T04:00:00Z
              validation: 2026-06-04T04:00:00Z/2026-06-04T08:00:00Z
              test: 2026-06-04T08:00:00Z/2026-06-04T12:00:00Z
            parameter_grid:
              fast: []
            """.trimIndent(),
        )

        assertThatThrownBy { ExperimentPlanLoader.load(planPath) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("parameter_grid.fast")
    }
}
