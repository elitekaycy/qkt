package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarsReplayBacktestTest : BarsReplayFixture() {
    @Test
    fun `backtest --bars replays built bars and trades`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 3)
        val build =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "XAUUSD",
                        "--tf",
                        "15m",
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-05",
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(build).isEqualTo(ExitCodes.SUCCESS)

        val out = ByteArrayOutputStream()
        val orig = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
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
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(orig)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("\"trades\":")
    }

    @Test
    fun `backtest --bars ignores tick-store holes without --allow-incomplete`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 3)
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "build-bars",
                    "XAUUSD",
                    "--tf",
                    "15m",
                    "--from",
                    "2024-01-02",
                    "--to",
                    "2024-01-05",
                    "--data-root",
                    dataRoot.toString(),
                ),
            ),
        ).run()
        // Punch a hole in the TICK store after the bars are built: a --bars run must not care.
        Files.delete(dataRoot.resolve("symbols").resolve("XAUUSD").resolve("2024-01-03.bin"))
        // No --allow-incomplete: the tick hole would throw IncompleteDataException if --bars still
        // validated the tick store. It must not, because --bars only reads the bar store.
        val code =
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
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `backtest --bars errors when bars are not built`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedTicks(dataRoot, days = 1)
        val code =
            BacktestCommand(
                Args(
                    arrayOf(
                        "backtest",
                        strategyFile(dir).toString(),
                        "--from",
                        "2024-01-02",
                        "--to",
                        "2024-01-03",
                        "--data-root",
                        dataRoot.toString(),
                        "--no-fetch",
                        "--allow-incomplete",
                        "--bars",
                        "--json",
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
