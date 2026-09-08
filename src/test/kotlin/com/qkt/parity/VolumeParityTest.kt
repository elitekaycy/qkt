package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

/**
 * `<stream>.volume` must mean the same thing on every path a strategy can run on.
 *
 * The aggregator now falls back to counting ticks when a venue quotes without size, which
 * is the only way an MT5 spot-FX bar can carry a volume at all. That fallback is computed
 * from whatever ticks the path actually saw -- so the risk it introduces is not a wrong
 * number but a DIFFERENT number between tick replay, bar replay, and the live session. A
 * volume-gated entry that fires on one path and not another is precisely the divergence
 * class this suite exists to catch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VolumeParityTest {
    @org.junit.jupiter.api.BeforeAll
    fun quiet() = QuietEngineLogs.silence()

    @org.junit.jupiter.api.AfterAll
    fun restore() = QuietEngineLogs.restore()

    private fun strategy(
        tempDir: Path,
        name: String,
        body: String,
    ): Path {
        val p = tempDir.resolve("$name.qkt")
        Files.writeString(
            p,
            """
            STRATEGY $name VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
            $body
            """.trimIndent(),
        )
        return p
    }

    @Test
    fun `a volume-gated entry fires identically on ticks, bars and live paper`(
        @TempDir tempDir: Path,
    ) {
        // The gate is deliberately loose: any non-zero volume admits the trade. Before the
        // fallback this condition was unreachable on a size-less feed, so this also pins that
        // volume is populated at all.
        val path =
            strategy(
                tempDir,
                "volume_gate",
                """
                WHEN x.close = 100 AND x.volume > 0 AND POSITION.x = 0
                THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                """.trimIndent(),
            )
        val r =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                closes = listOf("100", "100", "100"),
                expectedTradeCount = 1,
                startingBalance = BigDecimal("100000"),
            )
        assertThat(r.backtest.trades).hasSize(1)
    }

    @Test
    fun `a relative-volume gate holds parity across every path`(
        @TempDir tempDir: Path,
    ) {
        // The shape the research produced: compare this bar's volume against its own recent
        // average. It is the ratio that must agree across paths, not the raw count.
        val path =
            strategy(
                tempDir,
                "thin_gate",
                """
                WHEN x.volume < sma(x.volume, 5) * 2.0 AND x.close = 100 AND POSITION.x = 0
                THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                """.trimIndent(),
            )
        val r =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                closes = List(10) { "100" },
                expectedTradeCount = 1,
                startingBalance = BigDecimal("100000"),
            )
        assertThat(r.backtest.trades).hasSize(1)
    }
}
