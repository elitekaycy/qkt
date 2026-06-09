package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepCommandTest {
    private fun strategy(dir: Path): Path {
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            LET fast = 3
            RULES
                WHEN ema(gold.close, fast) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1
                WHEN ema(gold.close, fast) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        return strat
    }

    @Test
    fun `sweep over a two-point grid runs both combos and ranks them`(
        @TempDir dir: Path,
    ) {
        val args =
            Args(
                arrayOf(
                    "sweep",
                    strategy(dir).toString(),
                    "--from",
                    "2026-06-04",
                    "--to",
                    "2026-06-05",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--param",
                    "fast=2,3",
                    "--rank",
                    "totalPnL",
                ),
            )
        val code = SweepCommand(args, fetcherOverride = FakeXauFetcher).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `an unknown rank metric is rejected`(
        @TempDir dir: Path,
    ) {
        val args =
            Args(
                arrayOf(
                    "sweep",
                    strategy(dir).toString(),
                    "--from",
                    "2026-06-04",
                    "--to",
                    "2026-06-05",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--param",
                    "fast=2,3",
                    "--rank",
                    "bogus",
                ),
            )
        val code = SweepCommand(args, fetcherOverride = FakeXauFetcher).run()
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
