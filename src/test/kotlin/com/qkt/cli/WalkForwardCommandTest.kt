package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WalkForwardCommandTest {
    @Test
    fun `walkforward produces folds over a fitting window`(
        @TempDir dir: Path,
    ) {
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
        val args =
            Args(
                arrayOf(
                    "walkforward",
                    strat.toString(),
                    "--from",
                    "2026-06-04T00:00:00",
                    "--to",
                    "2026-06-04T12:00:00",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--param",
                    "fast=2,3",
                    "--train",
                    "6h",
                    "--test",
                    "3h",
                    "--step",
                    "3h",
                    "--rank",
                    "totalPnL",
                ),
            )
        val code = WalkForwardCommand(args, fetcherOverride = FakeXauFetcher).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }
}
