package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** An experiment run over built bars sweeps each combination through the bar replay, one engine per combination. */
class ExperimentBarsSweepTest : BarsReplayFixture() {
    @Test
    fun `an experiment with bars ranks its combinations and records the run`(
        @TempDir dir: Path,
    ) {
        val data = dir.resolve("data")
        seedTicks(data, 1)
        val built =
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
                        "2024-01-02",
                        "--data-root",
                        data.toString(),
                    ),
                ),
            ).run()
        assertThat(built).isEqualTo(ExitCodes.SUCCESS)
        val strategy =
            Files.writeString(
                dir.resolve("s.qkt"),
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 15m
                LET fast = 3
                RULES
                    WHEN ema(gold.close, fast) CROSSES ABOVE ema(gold.close, 9)
                    THEN BUY gold SIZING 0.1
                    WHEN ema(gold.close, fast) CROSSES BELOW ema(gold.close, 9)
                    THEN CLOSE gold
                """.trimIndent(),
            )
        val plan =
            Files.writeString(
                dir.resolve("plan.yaml"),
                "name: bars experiment\nstrategy: $strategy\nprimary_metric: totalPnL\nsplits:\n" +
                    "  train: 2024-01-02T00:00:00Z/2024-01-02T08:00:00Z\n" +
                    "  validation: 2024-01-02T08:00:00Z/2024-01-02T16:00:00Z\n" +
                    "  test: 2024-01-02T16:00:00Z/2024-01-03T00:00:00Z\n" +
                    "parameter_grid:\n  fast: [2, 3]\nselection:\n  top_n: 1\n",
            )

        val run =
            captureCliRun {
                ExperimentCommand(
                    Args(
                        arrayOf(
                            "experiment",
                            "run",
                            "--plan",
                            plan.toString(),
                            "--bars",
                            "--no-fetch",
                            "--data-root",
                            data.toString(),
                            "--registry-dir",
                            dir.resolve("registry").toString(),
                            "--out-dir",
                            dir.resolve("out").toString(),
                        ),
                    ),
                ).run()
            }

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("experiment: bars experiment")
    }
}
