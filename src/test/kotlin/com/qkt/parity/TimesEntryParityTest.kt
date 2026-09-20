package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.parity.TimesEntryCases.cases
import com.qkt.parity.TimesEntryFixtures.X
import com.qkt.parity.TimesEntryFixtures.bracket
import com.qkt.parity.TimesEntryFixtures.x
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/**
 * `TIMES <expr>` -- one entry emitted N times in one evaluation -- held to the tick/bar/live
 * parity contract, and pinned equal to the hand-written form it replaces.
 *
 * The shape under test is the one a burst scalper writes: a condition resolves, N bracketed
 * orders go out at once, each its own ticket with its own protection. Before this clause that
 * meant writing the action N times separated by `;`. The first case here proves the clause is
 * exactly that expansion, trade for trade; the rest exercise what the expansion could not do --
 * a count decided at fire time by a condition, an indicator, or the account -- and the
 * multi-symbol, multi-condition sequence a real bot runs.
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class TimesEntryParityTest {
    @org.junit.jupiter.api.BeforeAll
    fun quietEngineLogs() = QuietEngineLogs.silence()

    @org.junit.jupiter.api.AfterAll
    fun restoreEngineLogs() = QuietEngineLogs.restore()

    @TestFactory
    fun `TIMES entries match ticks bars and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, case.source)
                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = path,
                        candlesBySymbol = case.candles,
                        window = TimeWindow.ONE_MINUTE,
                        closeOnlyTicks = true,
                        expectedTradeCount = case.expected.size,
                        startingBalance = case.startingBalance,
                        runawayMaxRoundTrips = case.runawayMaxRoundTrips,
                    )
                assertThat(result.backtest.trades.map { it.side to it.symbol })
                    .containsExactlyElementsOf(case.expected)
            }
        }

    @Test
    fun `TIMES N is trade-for-trade the same as writing the action N times`(
        @TempDir tempDir: Path,
    ) {
        val sugar = tempDir.resolve("sugar.qkt")
        Files.writeString(
            sugar,
            """
            STRATEGY sugar VERSION 1
            SYMBOLS x = $X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 0.01 $bracket TIMES 12
            """.trimIndent(),
        )
        val written = tempDir.resolve("written.qkt")
        Files.writeString(
            written,
            """
            STRATEGY written VERSION 1
            SYMBOLS x = $X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN ${List(12) { "BUY x SIZING 0.01 $bracket" }.joinToString("\n                 ; ")}
            """.trimIndent(),
        )
        val tape = x("100", "100", "160", "160")
        val a =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = sugar,
                candlesBySymbol = tape,
                window = TimeWindow.ONE_MINUTE,
                closeOnlyTicks = true,
                expectedTradeCount = 24,
                startingBalance = BigDecimal("100000"),
                runawayMaxRoundTrips = 500,
            )
        val b =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = written,
                candlesBySymbol = tape,
                window = TimeWindow.ONE_MINUTE,
                closeOnlyTicks = true,
                expectedTradeCount = 24,
                startingBalance = BigDecimal("100000"),
                runawayMaxRoundTrips = 500,
            )

        fun shape(r: DslParityHarness.Result) =
            r.backtest.trades.map { listOf(it.side, it.symbol, it.quantity, it.price, it.timestamp.toString()) }
        assertThat(shape(a)).isEqualTo(shape(b))
        assertThat(a.backtest.pnl).isEqualTo(b.backtest.pnl)
    }
}
