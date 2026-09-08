package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.marketdata.Candle
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

    private data class Case(
        val id: String,
        val candles: Map<String, List<Candle>>,
        val expected: List<Pair<String, String>>,
        val source: String,
        val startingBalance: BigDecimal = BigDecimal("100000"),
        /** Burst cases must raise the live round-trip breaker; see the dedicated test below. */
        val runawayMaxRoundTrips: Int = 500,
    )

    private fun leg(
        side: String,
        symbol: String,
        n: Int,
    ) = List(n) { side to symbol }

    private fun x(vararg closes: String) = mapOf(X to candles(X, closes.toList()))

    private val bracket = "BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }"

    private val cases =
        listOf(
            Case(
                id = "times_thirty_market_burst",
                candles = x("100", "100", "100"),
                expected = leg("BUY", X, 30),
                source =
                    """
                    STRATEGY times_thirty_market_burst VERSION 1
                    SYMBOLS x = $X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01 $bracket TIMES 30
                    """.trimIndent(),
            ),
            Case(
                id = "times_count_from_condition",
                candles = x("100", "100", "100"),
                // close = 100 makes `strong` true, so the CASE picks 5 over 1
                expected = leg("BUY", X, 5),
                source =
                    """
                    STRATEGY times_count_from_condition VERSION 1
                    SYMBOLS x = $X EVERY 1m
                    LET strong = x.close >= 100
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01 $bracket TIMES CASE WHEN strong THEN 5 ELSE 1 END
                    """.trimIndent(),
            ),
            Case(
                id = "times_count_from_account_balance",
                candles = x("100", "100", "100"),
                // 100000 / 25000 = 4
                expected = leg("BUY", X, 4),
                source =
                    """
                    STRATEGY times_count_from_account_balance VERSION 1
                    SYMBOLS x = $X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01 $bracket TIMES floor(ACCOUNT.balance / 25000)
                    """.trimIndent(),
            ),
            Case(
                id = "times_count_from_atr_range",
                // atr(3) on a flat tape is 0, so the first fire admits min(1 + 0, 8) = 1 leg;
                // the 97 bar closes it, and by the 106 bar the range has grown to ~3, so the
                // second fire admits four -- the count follows the market, not the source
                candles = x("100", "100", "100", "100", "100", "100", "97", "97", "106", "115", "115", "115"),
                expected = leg("BUY", X, 1) + leg("SELL", X, 1) + leg("BUY", X, 4),
                source =
                    """
                    STRATEGY times_count_from_atr_range VERSION 1
                    SYMBOLS x = $X EVERY 1m WARMUP 3 BARS
                    LET legs = min(1 + floor(atr(x.candle, 3)), 8)
                    RULES
                      WHEN x.close >= 100 AND POSITION.x = 0 AND atr(x.candle, 3) IS NOT NULL
                      THEN BUY x SIZING 0.01 $bracket TIMES legs

                      WHEN POSITION.x > 0 AND x.close < 100
                      THEN CLOSE x
                    """.trimIndent(),
            ),
            Case(
                id = "times_zero_emits_nothing",
                candles = x("100", "100", "100"),
                expected = emptyList(),
                source =
                    """
                    STRATEGY times_zero_emits_nothing VERSION 1
                    SYMBOLS x = $X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01 $bracket TIMES 0
                    """.trimIndent(),
            ),
            Case(
                id = "times_fraction_truncates",
                candles = x("100", "100", "100"),
                expected = leg("BUY", X, 2),
                source =
                    """
                    STRATEGY times_fraction_truncates VERSION 1
                    SYMBOLS x = $X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01 $bracket TIMES 2.9
                    """.trimIndent(),
            ),
            Case(
                id = "times_each_repetition_is_its_own_stack",
                candles = x("100", "100", "101", "102", "102"),
                // three stacks of three layers: seeds at 100, layer 2 at 101, layer 3 at 102
                expected = leg("BUY", X, 9),
                source =
                    """
                    STRATEGY times_each_repetition_is_its_own_stack VERSION 1
                    SYMBOLS x = $X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01 STACK 3 SPACING 1 ABOVE WITHIN 30m $bracket TIMES 3
                    """.trimIndent(),
            ),
            Case(
                id = "times_gold_buys_and_eurusd_sells_in_one_rule",
                // gold only reaches the trigger on bar 3, by which time eur has closed bars --
                // a cross-stream bracketed order needs its own stream to have closed one (see
                // `a bracketed order on a second stream is skipped ...` below)
                candles =
                    mapOf(
                        X to candles(X, listOf("99", "99", "100", "100", "100")),
                        Y to candles(Y, listOf("200", "200", "200", "200", "200")),
                    ),
                expected = leg("BUY", X, 10) + leg("SELL", Y, 10),
                source =
                    """
                    STRATEGY times_gold_buys_and_eurusd_sells_in_one_rule VERSION 1
                    SYMBOLS
                      gold = $X EVERY 1m
                      eur = $Y EVERY 1m
                    RULES
                      WHEN gold.close = 100 AND POSITION.gold = 0 AND POSITION.eur = 0
                      THEN BUY gold SIZING 0.01 $bracket TIMES 10
                         ; SELL eur SIZING 0.01 $bracket TIMES 10
                    """.trimIndent(),
            ),
            // The full story the goal describes: one condition fires 50 gold buys; they are
            // closed; a second condition fires 50 gold sells; they are closed; a third fires
            // 10 gold buys and 10 eur sells together. Brackets are wide enough that the tape
            // never reaches them, so every exit is the strategy's own CLOSE.
            //
            // Note what CLOSE does to a burst: 50 open legs net into ONE closing order, not 50.
            // That is the netting model both the backtest and the paper live session run; a
            // hedging MT5 account closes each ticket instead. Sizing a burst therefore has to
            // account for the venue's position model -- see the stack reference.
            Case(
                id = "times_three_conditions_in_sequence",
                candles =
                    mapOf(
                        X to candles(X, listOf("99", "100", "101", "110", "109", "120", "120", "120")),
                        Y to candles(Y, listOf("200", "200", "200", "200", "200", "200", "200", "200")),
                    ),
                expected =
                    leg("BUY", X, 50) + leg("SELL", X, 1) +
                        leg("SELL", X, 50) + leg("BUY", X, 1) +
                        leg("BUY", X, 10) + leg("SELL", Y, 10),
                source =
                    """
                    STRATEGY times_three_conditions_in_sequence VERSION 1
                    SYMBOLS
                      gold = $X EVERY 1m
                      eur = $Y EVERY 1m
                    RULES
                      WHEN gold.close = 100 AND POSITION.gold = 0 AND TRADES.today = 0
                      THEN BUY gold SIZING 0.01 BRACKET { STOP LOSS BY 80, TAKE PROFIT BY 80 } TIMES 50

                      WHEN gold.close = 110 AND POSITION.gold = 0 AND TRADES.today = 50
                      THEN SELL gold SIZING 0.01 BRACKET { STOP LOSS BY 80, TAKE PROFIT BY 80 } TIMES 50

                      WHEN gold.close = 120 AND POSITION.gold = 0 AND POSITION.eur = 0 AND TRADES.today = 100
                      THEN BUY gold SIZING 0.01 BRACKET { STOP LOSS BY 80, TAKE PROFIT BY 80 } TIMES 10
                         ; SELL eur SIZING 0.01 BRACKET { STOP LOSS BY 80, TAKE PROFIT BY 80 } TIMES 10

                      WHEN POSITION.gold <> 0 AND gold.close = 101
                      THEN CLOSE gold

                      WHEN POSITION.gold <> 0 AND gold.close = 109
                      THEN CLOSE gold
                    """.trimIndent(),
            ),
        )

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

    private companion object {
        const val X = "BACKTEST:X"
        const val Y = "BACKTEST:Y"

        fun candles(
            symbol: String,
            closes: List<String>,
        ): List<Candle> =
            closes.mapIndexed { index, close ->
                val price = BigDecimal(close)
                Candle(
                    symbol = symbol,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = BigDecimal.ONE,
                    startTime = index * 60_000L,
                    endTime = (index + 1) * 60_000L,
                )
            }
    }
}
