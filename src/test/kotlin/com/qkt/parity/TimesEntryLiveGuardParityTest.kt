package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.parity.TimesEntryFixtures.X
import com.qkt.parity.TimesEntryFixtures.Y
import com.qkt.parity.TimesEntryFixtures.bracket
import com.qkt.parity.TimesEntryFixtures.candles
import com.qkt.parity.TimesEntryFixtures.x
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class TimesEntryLiveGuardParityTest {
    @org.junit.jupiter.api.BeforeAll
    fun quietEngineLogs() = QuietEngineLogs.silence()

    @org.junit.jupiter.api.AfterAll
    fun restoreEngineLogs() = QuietEngineLogs.restore()

    @Test
    fun `a burst that closes more than ten positions in ten minutes trips the live runaway breaker`(
        @TempDir tempDir: Path,
    ) {
        // The single most important operational fact about burst entries. The runaway breaker
        // counts CLOSING FILLS per strategy in a ten-minute window, defaults to ten, runs only
        // in the live assembly, and halts the strategy PERSISTENTLY -- an operator has to run
        // `qkt resume`. A strategy that opens twelve positions and lets its brackets take them
        // out is a fill/re-enter loop as far as the breaker is concerned, so it halts live while
        // the backtest sails through. Raise `max_round_trips_10m` for any burst strategy, and
        // size it above the busiest ten minutes the strategy can have.
        val path = tempDir.resolve("burst_breaker.qkt")
        Files.writeString(
            path,
            """
            STRATEGY burst_breaker VERSION 1
            SYMBOLS x = $X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 } TIMES 12
            """.trimIndent(),
        )
        // twelve entries, all taken out by their targets on the 160 bar -> twelve round trips
        val tape = x("100", "100", "160", "160")
        val tripped =
            org.junit.jupiter.api.assertThrows<AssertionError> {
                GeneratedStrategyReplay.assertTickBarAndLiveParity(
                    path = path,
                    candlesBySymbol = tape,
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = 24,
                    startingBalance = BigDecimal("100000"),
                )
            }
        assertThat(tripped).hasMessageContaining("runaway breaker").hasMessageContaining("round trips")

        // the same strategy under a threshold sized for it holds full backtest/live parity
        val ok =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                candlesBySymbol = tape,
                window = TimeWindow.ONE_MINUTE,
                closeOnlyTicks = true,
                expectedTradeCount = 24,
                startingBalance = BigDecimal("100000"),
                runawayMaxRoundTrips = 500,
            )
        assertThat(ok.backtest.halts).isEmpty()
        assertThat(ok.live.halts).isEmpty()
    }

    @Test
    fun `a bracketed order on a second stream is skipped until that stream has closed a bar`(
        @TempDir tempDir: Path,
    ) {
        // A cross-stream entry prices itself from the target stream's own last CLOSED candle,
        // because the rule is firing on a different stream's bar and the target's current bar is
        // still open. Before that stream has closed anything there is no price, and the order is
        // dropped -- with a warn line, but no rejection and no suppressed signal, so it is
        // invisible in the trade record. A plain unbracketed order is unaffected: it needs no
        // price to construct. Declare WARMUP on every stream a rule can trade, or trigger off a
        // bar late enough that the co-traded stream has closed one.
        fun run(
            id: String,
            goldCloses: List<String>,
        ) = GeneratedStrategyReplay.assertTickBarAndLiveParity(
            path =
                tempDir.resolve("$id.qkt").also {
                    Files.writeString(
                        it,
                        """
                        STRATEGY $id VERSION 1
                        SYMBOLS
                          gold = $X EVERY 1m
                          eur = $Y EVERY 1m
                        RULES
                          WHEN gold.close = 100 AND POSITION.gold = 0 AND POSITION.eur = 0
                          THEN BUY gold SIZING 0.01 $bracket
                             ; SELL eur SIZING 0.01 $bracket
                        """.trimIndent(),
                    )
                },
            candlesBySymbol =
                mapOf(
                    X to candles(X, goldCloses),
                    Y to candles(Y, List(goldCloses.size) { "200" }),
                ),
            window = TimeWindow.ONE_MINUTE,
            closeOnlyTicks = true,
            expectedTradeCount = if (goldCloses.first() == "100") 1 else 2,
            startingBalance = BigDecimal("100000"),
        )

        // fires on the very first bar: eur has closed nothing, so only the gold leg is placed --
        // the dropped leg is reported as a suppressed signal (see CrossStreamSuppressionTest)
        assertThat(run("first_bar", listOf("100", "100", "100")).backtest.trades.map { it.symbol })
            .containsExactly(X)
        // fires once eur has closed a bar: both legs are placed
        assertThat(run("later_bar", listOf("99", "99", "100", "100")).backtest.trades.map { it.symbol })
            .containsExactly(X, Y)
    }
}
