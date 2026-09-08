package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/**
 * Deep stacks driven by fast signals -- the scalping shape, held to the same parity contract as
 * everything else.
 *
 * The strategies a scalper actually runs are not the ones the existing parity suites cover. They
 * stack ten, fifteen, twenty layers off one signal instead of three; they take profit at a
 * distance chosen to clear spread and commission rather than at a round number; they run with a
 * protective stop and exit on a rule rather than a target; and they do all of it repeatedly inside
 * a few minutes rather than once over a day. Each of those is a different load on the same
 * machinery: layer triggers competing with a bracket on the same tick, a cancel racing partially
 * filled layers, and re-entry the moment a scalp closes.
 *
 * Two facts about the engine shape every expectation below. A market order signalled on one tick
 * fills on the next, so each tape opens with its entry price twice: once to fire the rule, once to
 * fill the seed at that price. And the two exit paths book differently: the outer bracket applies
 * to every layer at that layer's own fill and closes one leg at a time -- three filled layers
 * stopped out are three SELL fills -- while a rule-driven `CLOSE` nets the whole stack into one
 * exit. Both are what a hedging venue would actually book for that instruction; a strategy that
 * wants a basket-level target expresses it as a rule over `POSITION.x.entry_price`, not a bracket.
 *
 * Every case runs through all four paths the generated replay harness drives -- tick backtest, bar
 * backtest, tick-resolved source, and a live paper session -- so a divergence between how a stack
 * fills on ticks and how it fills on bars fails here rather than in production.
 */
class ScalpStackParityTest {
    private data class Case(
        val id: String,
        val prices: List<String>,
        val expectedSides: List<String>,
        val source: String,
        /** The tape is meant to leave nothing open: every fill has been closed by the end. */
        val endsFlat: Boolean = false,
    )

    /** A price walk from `from` to `to` inclusive, one point per step -- one candle per level. */
    private fun ramp(
        from: Int,
        to: Int,
    ): List<String> = if (from <= to) (from..to).map { it.toString() } else (from downTo to).map { it.toString() }

    /** The entry tape: the rule fires on the first `100`, the seed fills on the second. */
    private fun entry(vararg then: String): List<String> = listOf("100", "100") + then

    private fun buys(n: Int) = List(n) { "BUY" }

    private fun sells(n: Int) = List(n) { "SELL" }

    private fun single(
        id: String,
        action: String,
        prices: List<String>,
        expectedSides: List<String>,
        endsFlat: Boolean = false,
    ) = Case(
        id = id,
        prices = prices,
        expectedSides = expectedSides,
        endsFlat = endsFlat,
        source =
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN ${action.prependIndent("  ").trimStart()}
            """.trimIndent(),
    )

    private val cases =
        listOf(
            // -- deep stacks: every layer fills, one level per tick ------------------------------
            single(
                id = "stack_10_layers_all_fill",
                action = "BUY x SIZING 0.01 STACK 10 SPACING 1 ABOVE WITHIN 30m",
                prices = entry(*ramp(101, 109).toTypedArray(), "109"),
                expectedSides = buys(10),
            ),
            single(
                id = "stack_15_layers_all_fill",
                action = "BUY x SIZING 0.01 STACK 15 SPACING 1 ABOVE WITHIN 30m",
                prices = entry(*ramp(101, 114).toTypedArray(), "114"),
                expectedSides = buys(15),
            ),
            single(
                id = "stack_20_layers_all_fill",
                action = "BUY x SIZING 0.01 STACK 20 SPACING 1 ABOVE WITHIN 30m",
                prices = entry(*ramp(101, 119).toTypedArray(), "119"),
                expectedSides = buys(20),
            ),
            // -- a single jump crossing every trigger at once -----------------------------------
            single(
                id = "stack_20_layers_one_gap_crosses_all",
                action = "BUY x SIZING 0.01 STACK 20 SPACING 1 ABOVE WITHIN 30m",
                prices = entry("119", "119"),
                expectedSides = buys(20),
            ),
            // -- the scale question: a hundred dependent layers, stepwise and in one gap -----------
            single(
                id = "stack_100_layers_all_fill",
                action = "BUY x SIZING 0.01 STACK 100 SPACING 1 ABOVE WITHIN 4h",
                prices = entry(*ramp(101, 199).toTypedArray(), "199"),
                expectedSides = buys(100),
            ),
            single(
                id = "stack_100_layers_one_gap_crosses_all",
                action = "BUY x SIZING 0.01 STACK 100 SPACING 1 ABOVE WITHIN 4h",
                prices = entry("199", "199"),
                expectedSides = buys(100),
            ),
            // -- a hundred independent legs, each with its own bracket, firing as MFE climbs ------
            Case(
                id = "stack_at_100_tiers_independent",
                prices = entry(*ramp(101, 200).toTypedArray(), "200"),
                expectedSides = buys(101),
                source =
                    """
                    STRATEGY stack_at_100_tiers_independent VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01
                        BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 900 }
                    ${(1..100).joinToString(
                        "\n",
                    ) {
                        "    STACK_AT MFE >= $it WITHIN 4h SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 900 }"
                    }}
                    """.trimIndent(),
            ),
            // -- partial fill, then the time fence abandons the remainder -------------------------
            single(
                id = "stack_20_layers_partial_then_abandon",
                action = "BUY x SIZING 0.01 STACK 20 SPACING 1 ABOVE WITHIN 3m",
                prices = entry("101", "102", "103", *List(8) { "103" }.toTypedArray()),
                expectedSides = buys(4),
            ),
            // -- average-down: layers add against the position ----------------------------------
            single(
                id = "stack_10_layers_below_average_down",
                action = "BUY x SIZING 0.01 STACK 10 SPACING 1 BELOW WITHIN 30m",
                prices = entry(*ramp(99, 91).toTypedArray(), "91"),
                expectedSides = buys(10),
            ),
            // -- the scalp itself: a target sized to clear spread and commission ----------------
            single(
                id = "scalp_tp_clears_costs",
                action = "BUY x SIZING 0.01 BRACKET { STOP LOSS BY 3, TAKE PROFIT BY 8 }",
                prices = entry("104", "108", "108"),
                expectedSides = listOf("BUY", "SELL"),
                endsFlat = true,
            ),
            // -- the outer bracket applies to every layer at that layer's own fill: layer k's target
            //    is fill_k + 12, so the ten targets sit at 112..121 and each leg banks its own 12 ----
            single(
                id = "stack_10_then_per_leg_tp_closes_every_leg",
                action =
                    """
                    BUY x SIZING 0.01
                      STACK 10 SPACING 1 ABOVE WITHIN 30m
                      BRACKET { STOP LOSS BY 20, TAKE PROFIT BY 12 }
                    """.trimIndent(),
                prices = entry(*ramp(101, 121).toTypedArray(), "121"),
                expectedSides = buys(10) + sells(10),
                endsFlat = true,
            ),
            single(
                id = "stack_partial_then_shared_sl_closes_every_leg",
                action =
                    """
                    BUY x SIZING 0.01
                      STACK 10 SPACING 1 ABOVE WITHIN 30m
                      BRACKET { STOP LOSS BY 4, TAKE PROFIT BY 40 }
                    """.trimIndent(),
                prices = entry("101", "102", "96", "96"),
                expectedSides = buys(3) + sells(3),
                endsFlat = true,
            ),
            // -- "stop only": the engine refuses a bare stop, so the supported shape is a far
            //    target plus a rule-driven exit. Here the exit is a CANCEL of the unfilled layers
            //    followed by a CLOSE of what did fill, fired by a price condition. --------------
            Case(
                id = "stack_stop_only_cancel_then_close",
                prices = entry("101", "102", "90", "90", "90"),
                // three layers fill; at 90 the rule cancels the seven pending layers and CLOSE nets
                // the three filled ones into one 0.03 exit; the far target is never reached
                expectedSides = buys(3) + sells(1),
                endsFlat = true,
                source =
                    """
                    STRATEGY stack_stop_only_cancel_then_close VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01
                        STACK 10 SPACING 1 ABOVE WITHIN 30m
                        BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 500 }

                      WHEN x.close = 90 AND POSITION.x > 0
                      THEN CANCEL x; CLOSE x
                    """.trimIndent(),
            ),
            // -- basket exit: no per-leg target does the work; a rule closes the whole stack once
            //    price clears the stack's average entry by a fixed distance ------------------------
            Case(
                id = "stack_5_basket_close_on_avg_entry_plus_6",
                prices = entry("101", "102", "103", "104", "110", "110"),
                // five layers fill at 100..104 (average 102); 110 clears 102 + 6, so the rule
                // closes the whole basket in one netted 0.05 exit
                expectedSides = buys(5) + sells(1),
                endsFlat = true,
                source =
                    """
                    STRATEGY stack_5_basket_close_on_avg_entry_plus_6 VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01
                        STACK 5 SPACING 1 ABOVE WITHIN 30m
                        BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 500 }

                      WHEN POSITION.x > 0 AND x.close >= POSITION.x.entry_price + 6
                      THEN CANCEL x; CLOSE x
                    """.trimIndent(),
            ),
            // -- flipping: three scalps back to back, each closed by its target ------------------
            Case(
                id = "three_scalps_back_to_back",
                prices = listOf("100", "100", "103", "100", "100", "103", "100", "100", "103", "103"),
                expectedSides = listOf("BUY", "SELL", "BUY", "SELL", "BUY", "SELL"),
                source =
                    """
                    STRATEGY three_scalps_back_to_back VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 3 }
                    """.trimIndent(),
                endsFlat = true,
            ),
            // -- independent legs: three MFE tiers each with its own bracket --------------------
            Case(
                id = "stack_at_three_tiers_independent_brackets",
                prices = entry("105", "110", "115", "115"),
                // primary fills at 100; tiers fire as MFE crosses 5, 10, 15
                expectedSides = buys(4),
                source =
                    """
                    STRATEGY stack_at_three_tiers_independent_brackets VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.03
                        BRACKET { STOP LOSS BY 30, TAKE PROFIT BY 60 }
                        STACK_AT MFE >= 5 WITHIN 30m SIZING 0.01 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 40 }
                        STACK_AT MFE >= 10 WITHIN 30m SIZING 0.01 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 40 }
                        STACK_AT MFE >= 15 WITHIN 30m SIZING 0.01 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 40 }
                    """.trimIndent(),
            ),
        )

    @TestFactory
    fun `scalping stacks match ticks bars and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, case.source)

                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = path,
                        closes = case.prices,
                        expectedTradeCount = case.expectedSides.size,
                    )

                assertThat(result.backtest.trades.map { it.side })
                    .containsExactlyElementsOf(case.expectedSides)
                if (case.endsFlat) {
                    assertThat(result.backtest.positions.filter { it.quantity.toBigDecimal().signum() != 0 })
                        .`as`("every leg opened on this tape should have been closed")
                        .isEmpty()
                    assertThat(result.live.positions.filter { it.quantity.toBigDecimal().signum() != 0 }).isEmpty()
                }
            }
        }
}
