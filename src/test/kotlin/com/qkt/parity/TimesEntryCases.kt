package com.qkt.parity

import com.qkt.marketdata.Candle
import com.qkt.parity.TimesEntryFixtures.X
import com.qkt.parity.TimesEntryFixtures.Y
import com.qkt.parity.TimesEntryFixtures.bracket
import com.qkt.parity.TimesEntryFixtures.candles
import com.qkt.parity.TimesEntryFixtures.leg
import com.qkt.parity.TimesEntryFixtures.x
import java.math.BigDecimal

/** The TIMES strategies, their tapes and the trades each must produce in every replay mode. */
internal object TimesEntryCases {
    data class Case(
        val id: String,
        val candles: Map<String, List<Candle>>,
        val expected: List<Pair<String, String>>,
        val source: String,
        val startingBalance: BigDecimal = BigDecimal("100000"),
        /** Burst cases must raise the live round-trip breaker; see the dedicated test below. */
        val runawayMaxRoundTrips: Int = 500,
    )

    val cases =
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
}
