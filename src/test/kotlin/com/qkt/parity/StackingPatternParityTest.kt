package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/**
 * The order-mechanic shapes the published stacking bots are actually built out of, each held to
 * the tick/bar/live parity contract.
 *
 * [ScalpStackParityTest] answers "does a deep stack fill and unwind correctly". This file asks a
 * different question: of the patterns these strategies are assembled from -- a grid of resting
 * orders placed upfront, a martingale that grows each layer by a fixed ratio, a scale-out that
 * banks a position in fractions, a zone-recovery hedge that answers an adverse move with the
 * opposite side -- which can this DSL express at all, and does each one replay identically?
 *
 * Where a pattern is expressible the case pins its mechanics. Where it is not, that is worth
 * knowing precisely, and the sibling `StackingPatternLimitsTest` records which ones and why.
 */
class StackingPatternParityTest {
    private data class Case(
        val id: String,
        val prices: List<String>,
        val expectedSides: List<String>,
        val source: String,
        val endsFlat: Boolean = false,
    )

    private fun buys(n: Int) = List(n) { "BUY" }

    private fun sells(n: Int) = List(n) { "SELL" }

    private fun entry(vararg then: String): List<String> = listOf("100", "100") + then

    private val cases =
        listOf(
            // -- martingale: each layer twice the last, from the layer-list form. The adds use
            //    LIMIT AT rather than bare AT: a bare `AT` priced BELOW the seed is the one layer
            //    form that does not hold parity (see StackingPatternLimitsTest) --------------------
            Case(
                id = "martingale_doubling_layers",
                prices = entry("98", "96", "94", "94"),
                expectedSides = buys(4),
                source =
                    """
                    STRATEGY martingale_doubling_layers VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x STACK [
                             0.01,
                             0.02 LIMIT AT entry - 2,
                             0.04 LIMIT AT entry - 4,
                             0.08 LIMIT AT entry - 6
                           ]
                           BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
            // -- zone-recovery ratio sizing: k = (Z + T) / T with Z = 2, T = 4 gives 1.5 --------
            Case(
                id = "zone_recovery_ratio_sizing",
                prices = entry("98", "96", "94", "94"),
                expectedSides = buys(4),
                source =
                    """
                    STRATEGY zone_recovery_ratio_sizing VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x STACK [
                             0.04,
                             0.06 LIMIT AT entry - 2,
                             0.09 LIMIT AT entry - 4,
                             0.14 LIMIT AT entry - 6
                           ]
                           BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
            // -- a resting bid ladder placed upfront, filled as price walks down ----------------
            Case(
                id = "limit_ladder_placed_upfront",
                prices = entry("99", "98", "97", "96", "95", "95"),
                expectedSides = buys(6),
                source =
                    """
                    STRATEGY limit_ladder_placed_upfront VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x STACK [
                             0.01,
                             0.01 LIMIT AT entry - 1,
                             0.01 LIMIT AT entry - 2,
                             0.01 LIMIT AT entry - 3,
                             0.01 LIMIT AT entry - 4,
                             0.01 LIMIT AT entry - 5
                           ]
                           BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
            // -- bidirectional grid: a stop above and a stop below, whichever triggers wins ------
            Case(
                id = "bidirectional_breakout_grid",
                prices = entry("100", "106", "106"),
                expectedSides = listOf("BUY"),
                source =
                    """
                    STRATEGY bidirectional_breakout_grid VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0 AND OPEN_ORDERS.x = 0
                      THEN OCO_ENTRY {
                             BUY x SIZING 0.01 ORDER_TYPE = STOP AT 105,
                             SELL x SIZING 0.01 ORDER_TYPE = STOP AT 95
                           }
                    """.trimIndent(),
            ),
            // -- recovery: an adverse move answered by the opposite side, sized to recover -------
            Case(
                id = "zone_recovery_opposite_leg",
                prices = entry("98", "96", "94", "94"),
                // the buy fills at 100; at 96 the rule adds a larger SELL against it
                expectedSides = listOf("BUY", "SELL"),
                source =
                    """
                    STRATEGY zone_recovery_opposite_leg VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.04
                           BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }

                      WHEN POSITION.x > 0 AND x.close <= POSITION.x.entry_price - 4
                      THEN SELL x SIZING 0.06
                           BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
            // -- pyramid that keeps adding while the trend holds, capped by the layer count ------
            Case(
                id = "trend_pyramid_capped_at_eight",
                prices = entry("101", "102", "103", "104", "105", "106", "107", "108", "108"),
                expectedSides = buys(8),
                source =
                    """
                    STRATEGY trend_pyramid_capped_at_eight VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01
                           STACK 8 SPACING 1 ABOVE WITHIN 30m
                           BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
        )

    @TestFactory
    fun `stacking patterns match ticks bars and live paper`(
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
                    assertThat(result.backtest.positions.filter { it.quantity.toBigDecimal().signum() != 0 }).isEmpty()
                }
            }
        }
}
