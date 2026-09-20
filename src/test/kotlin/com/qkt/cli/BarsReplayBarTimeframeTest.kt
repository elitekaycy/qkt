package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarsReplayBarTimeframeTest : BarsReplayFixture() {
    private fun runBarsArgs(
        dir: Path,
        dataRoot: Path,
        extra: Array<String>,
    ): Int {
        val orig = System.out
        return try {
            System.setOut(PrintStream(ByteArrayOutputStream()))
            BacktestCommand(
                Args(
                    arrayOf(
                        "backtest",
                        strategyFile(dir).toString(),
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-05",
                        "--data-root",
                        dataRoot.toString(),
                        "--no-fetch",
                        "--allow-incomplete",
                        "--bars",
                        "--json",
                    ) + extra,
                ),
            ).run()
        } finally {
            System.setOut(orig)
        }
    }

    private fun buildBars(
        dataRoot: Path,
        tf: String,
    ) {
        seedTicks(dataRoot, days = 3)
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "build-bars",
                    "XAUUSD",
                    "--tf",
                    tf,
                    "--from",
                    "2024-01-02",
                    "--to",
                    "2024-01-05",
                    "--data-root",
                    dataRoot.toString(),
                ),
            ),
        ).run()
    }

    @Test
    fun `--bar-tf pins the fill feed to the requested tf, erroring when it is not built`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        buildBars(dataRoot, "15m") // only 15m built; 1m is not
        // Auto-resolve uses the built 15m and succeeds.
        assertThat(runBarsArgs(dir, dataRoot, arrayOf())).isEqualTo(ExitCodes.SUCCESS)
        // --bar-tf 1m overrides the resolver to request 1m, which is not built -> the guardrail errors.
        assertThat(runBarsArgs(dir, dataRoot, arrayOf("--bar-tf", "1m"))).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `--bar-tf rejects a tf that does not divide the strategy timeframe`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        buildBars(dataRoot, "15m")
        // 2m does not divide the strategy's 15m, so rollup would be inexact -> reject before running.
        assertThat(runBarsArgs(dir, dataRoot, arrayOf("--bar-tf", "2m"))).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `plain bars reject mt5-sim because synthetic ticks void its fill model`(
        @TempDir dir: Path,
    ) {
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        val code =
            try {
                System.setErr(PrintStream(err))
                runBarsArgs(dir, dir.resolve("data"), arrayOf("--broker", "mt5-sim"))
            } finally {
                System.setErr(originalErr)
            }

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err.toString())
            .contains("--bars with --broker mt5-sim is unsafe")
            .contains("Use --bars --tick-fills or full tick replay")
    }
}
