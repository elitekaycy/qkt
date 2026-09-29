package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `--bars` picks the coarsest built timeframe that COVERS the window, not merely the coarsest built (#1275). */
class BarsReplayCoverageTest : BarsReplayFixture() {
    private fun buildBars(
        dataRoot: Path,
        tf: String,
        from: String,
        to: String,
    ) {
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "build-bars",
                    "XAUUSD",
                    "--tf",
                    tf,
                    "--from",
                    from,
                    "--to",
                    to,
                    "--data-root",
                    dataRoot.toString(),
                ),
            ),
        ).run()
    }

    /** Runs a strict `--bars` backtest (no `--allow-incomplete`); returns the exit code and stderr. */
    private fun runStrict(
        dir: Path,
        dataRoot: Path,
    ): Pair<Int, String> {
        val origOut = System.out
        val origErr = System.err
        val err = ByteArrayOutputStream()
        val code =
            try {
                System.setOut(PrintStream(ByteArrayOutputStream()))
                System.setErr(PrintStream(err))
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
                            "--bars",
                            "--json",
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(origOut)
                System.setErr(origErr)
            }
        return code to err.toString()
    }

    @Test
    fun `a coarser folder that does not cover the window yields to a finer one that does`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 3)
        buildBars(dataRoot, "1m", "2024-01-02", "2024-01-05")
        // Someone built 15m for the first day only: coarser, divides 15m, but incomplete.
        buildBars(dataRoot, "15m", "2024-01-02", "2024-01-03")

        val (code, err) = runStrict(dir, dataRoot)

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(err)
            .contains("15m covers 1/3 trading days")
            .contains("bar coverage BACKTEST:XAUUSD 3/3 trading days (1m)")
    }

    @Test
    fun `when no built folder covers the window the coarsest one's gaps are reported`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 3)
        buildBars(dataRoot, "1m", "2024-01-02", "2024-01-04")
        buildBars(dataRoot, "15m", "2024-01-02", "2024-01-03")

        val (code, err) = runStrict(dir, dataRoot)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("incomplete built bars for BACKTEST:XAUUSD: 1/3 trading days")
    }
}
