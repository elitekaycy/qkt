package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The summary's fill and cost lines come from what the replay did (#1437), on a real run. */
class BacktestExecutionFactsTest : BarsReplayFixture() {
    private fun summary(
        dir: Path,
        vararg extra: String,
    ): String {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 2)
        val buf = ByteArrayOutputStream()
        val orig = System.out
        try {
            System.setOut(PrintStream(buf))
            val code =
                BacktestCommand(
                    Args(
                        arrayOf(
                            "backtest",
                            strategyFile(dir).toString(),
                            "--from",
                            "2024-01-02",
                            "--to",
                            "2024-01-04",
                            "--data-root",
                            dataRoot.toString(),
                            "--no-fetch",
                            "--allow-incomplete",
                            "--no-report",
                        ) + extra,
                    ),
                ).run()
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(orig)
        }
        return buf.toString()
    }

    @Test
    fun `mt5-sim on mid-only ticks says its spread is synthetic`(
        @TempDir dir: Path,
    ) {
        val text = summary(dir, "--broker", "mt5-sim", "--slippage", "fixed-points:5")

        assertThat(text)
            .contains("Fills:      a synthetic 2-point spread around the middle price, where the data had no bid/ask")
            .contains("Slippage:   5 points against each market and stop fill")
            .contains("synthetic 2-point spread; no commission or swap")
            .doesNotContain("recorded bid/ask")
    }

    @Test
    fun `the paper broker says it fills at the middle price and ignores slippage`(
        @TempDir dir: Path,
    ) {
        val text = summary(dir, "--slippage", "fixed-points:5")

        assertThat(text)
            .contains("Fills:      middle price, no spread")
            .contains("Slippage:   none (the paper broker does not slip fills; --broker mt5-sim does)")
            .contains("Try --broker mt5-sim for a realistic fill model.")
    }
}
