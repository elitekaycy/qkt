package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestRunSelectionTest {
    private val section =
        BacktestSection(
            defaults = mapOf("from" to "2025-09-01"),
            strategy = "s/default.qkt",
            runs = mapOf("scalps" to BacktestRun(mapOf("from" to "2025-10-01"), strategy = "s/scalp.qkt")),
        )

    @Test
    fun `missing token falls back to the global strategy`() {
        val selection = BacktestRunSelection.select(null, section, fileExists = { false })
        assertThat(selection)
            .isEqualTo(
                BacktestRunSelection.Selection.File(
                    java.nio.file.Path
                        .of("s/default.qkt"),
                    configured = true,
                ),
            )
    }

    @Test
    fun `missing token without any backtest block keeps the old missing-argument error`() {
        val selection = BacktestRunSelection.select(null, BacktestSection(), fileExists = { false })
        val message = (selection as BacktestRunSelection.Selection.Error).message
        assertThat(message).contains("missing required argument: <strategy.qkt>")
        assertThat(message).contains("Usage: qkt backtest")
    }

    @Test
    fun `missing token with runs but no strategy errors with guidance`() {
        val selection = BacktestRunSelection.select(null, section.copy(strategy = null), fileExists = { false })
        val message = (selection as BacktestRunSelection.Selection.Error).message
        assertThat(message).contains("no strategy given")
        assertThat(message).contains("runs: scalps")
    }

    @Test
    fun `an existing file always wins`() {
        val selection = BacktestRunSelection.select("scalps", section, fileExists = { it == "scalps" })
        assertThat(selection).isEqualTo(
            BacktestRunSelection.Selection.File(
                java.nio.file.Path
                    .of("scalps"),
            ),
        )
    }

    @Test
    fun `a dotted token falls back to the run of that name`() {
        val selection = BacktestRunSelection.select("scalps.qkt", section, fileExists = { false })
        assertThat(selection).isEqualTo(
            BacktestRunSelection.Selection.Named("scalps", section.runs.getValue("scalps")),
        )
    }

    @Test
    fun `a bare token resolves the named run`() {
        val selection = BacktestRunSelection.select("scalps", section, fileExists = { false })
        assertThat(selection).isInstanceOf(BacktestRunSelection.Selection.Named::class.java)
    }

    @Test
    fun `an unknown bare token errors with runs and a suggestion`() {
        val selection = BacktestRunSelection.select("scalsp", section, fileExists = { false })
        val message = (selection as BacktestRunSelection.Selection.Error).message
        assertThat(message).contains("no file 'scalsp' and no run 'scalsp'")
        assertThat(message).contains("Did you mean 'scalps'?")
        assertThat(message).contains("runs: scalps")
    }

    @Test
    fun `a missing dotted path flows to the file check`() {
        val selection = BacktestRunSelection.select("nope.qkt", section, fileExists = { false })
        assertThat(selection).isEqualTo(
            BacktestRunSelection.Selection.File(
                java.nio.file.Path
                    .of("nope.qkt"),
            ),
        )
    }
}
