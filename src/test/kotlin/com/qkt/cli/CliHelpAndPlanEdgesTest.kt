package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Help text for an unmatched flag filter and a run with params, and an experiment's promotion intent. */
class CliHelpAndPlanEdgesTest {
    @Test
    fun `a flag filter that matches nothing says so and lists no flags`() {
        val help = CliHelp.forCommand("backtest", filter = "zzz-no-such-flag")

        assertThat(help.lines()).contains("  No flag matching 'zzz-no-such-flag'.")
        assertThat(help.lines().filter { it.startsWith("  --") }).isEmpty()
    }

    @Test
    fun `run help lists merged keys with their layer and the run's params`() {
        val merged =
            BacktestRunSelection.Merged(
                values = mapOf("to" to "2025-10-01", "from" to "2025-09-01"),
                params = listOf("fast=3", "slow=20"),
                provenance = mapOf("from" to "run:scalp"),
            )

        val lines = CliHelp.forRun("scalp", merged).lines().map { it.trimEnd() }

        assertThat(lines).contains("Run 'scalp' (qkt backtest scalp):")
        assertThat(lines).contains("  ${"from".padEnd(20)} 2025-09-01   (run:scalp)")
        assertThat(lines).contains("  ${"to".padEnd(20)} 2025-10-01   (global)")
        assertThat(lines).contains("  params: fast=3, slow=20")
        assertThat(CliHelp.forRun("bare", BacktestRunSelection.Merged())).doesNotContain("params:")
    }

    @Test
    fun `an experiment's promotion state must be a known lifecycle state`() {
        assertThat(ExperimentPromotionPlan(state = "shadow-live", rationale = "ok").state).isEqualTo("shadow-live")
        assertThat(ExperimentPromotionPlan().state).isNull()
        assertThatThrownBy { ExperimentPromotionPlan(state = "live") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "promotion.state must be one of draft, research, candidate, paper, shadow-live, small-capital, " +
                    "production, retired",
            )
    }
}
