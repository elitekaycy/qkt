package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WalkForwardCommandTest {
    private fun writeStrategy(dir: Path): Path {
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

    private fun wfArgs(
        strat: Path,
        dir: Path,
        vararg extra: String,
    ): Args =
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
                *extra,
            ),
        )

    @Test
    fun `walkforward produces folds over a fitting window`(
        @TempDir dir: Path,
    ) {
        val code =
            WalkForwardCommand(
                wfArgs(writeStrategy(dir), dir, "--rank", "totalPnL"),
                fetcherOverride = FakeXauFetcher,
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `walkforward --json emits one json object and never the ranking sentinel`(
        @TempDir dir: Path,
    ) {
        val out = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(out, true))
                WalkForwardCommand(
                    wfArgs(writeStrategy(dir), dir, "--rank", "sharpe", "--json"),
                    fetcherOverride = FakeXauFetcher,
                ).run()
            } finally {
                System.setOut(original)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val json = out.toString().lines().firstOrNull { it.trimStart().startsWith("{\"rank\"") }
        assertThat(json).isNotNull()
        assertThat(json!!)
            .contains("\"folds\":")
            .contains("\"meanInSample\":")
            .contains("\"meanOutOfSample\":")
            .contains("\"winnerStability\":")
            .contains("\"foldDetail\":")
        assertThat(json).doesNotContain("-1E18").doesNotContain("1000000000000000000")
    }
}
