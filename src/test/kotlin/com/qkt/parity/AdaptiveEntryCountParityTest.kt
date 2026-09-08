package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/**
 * How many entries a signal is allowed to produce, and how each one is sized.
 *
 * `STACK <n>` answers "always exactly n layers" -- the count is an integer literal the parser
 * folds at compile time, so it cannot be an expression, an account balance, or anything the
 * market decides. The strategies that want "one to n, depending", or "size this leg off ATR",
 * reach for different idioms, and this file pins which ones work.
 *
 * The load-bearing idiom is a position cap rather than a repeat count: a rule guarded by
 * `POSITION.<stream> < cap` fires again on each evaluation while the signal and the cap both
 * hold, so the leg count is decided at runtime by how long the condition survives. Sizing is
 * an expression in every entry form, so each of those legs can price itself off ATR, equity, or
 * account balance independently.
 */
class AdaptiveEntryCountParityTest {
    private data class Case(
        val id: String,
        val prices: List<String>,
        val expectedSides: List<String>,
        val source: String,
    )

    private fun buys(n: Int) = List(n) { "BUY" }

    private val cases =
        listOf(
            // -- fixed N at one condition, written out: N bracketed entries on the same tick ----
            Case(
                id = "fixed_five_entries_one_condition",
                prices = listOf("100", "100", "100"),
                expectedSides = buys(5),
                source =
                    """
                    STRATEGY fixed_five_entries_one_condition VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close = 100 AND POSITION.x = 0
                      THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                         ; BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                         ; BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                         ; BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                         ; BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
            // -- runtime-variable count. Conditions are EDGE-triggered (conditions.md), so a
            //    signal that simply stays true fires once; the count is driven by how many times
            //    the signal re-arms, bounded by the position cap. Three re-arms -> three legs. --
            Case(
                id = "variable_count_three_rearms",
                prices = listOf("100", "100", "99", "100", "99", "100", "99", "99"),
                expectedSides = buys(3),
                source =
                    """
                    STRATEGY variable_count_three_rearms VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close >= 100 AND POSITION.x < 0.05
                      THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
            // -- same strategy, more re-arms than the cap allows: the cap is what stops it -----
            Case(
                id = "variable_count_capped_at_three",
                prices = listOf("100", "100", "99", "100", "99", "100", "99", "100", "99", "100", "100"),
                expectedSides = buys(3),
                source =
                    """
                    STRATEGY variable_count_capped_at_three VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    RULES
                      WHEN x.close >= 100 AND POSITION.x < 0.03
                      THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
            // -- the count is chosen by a condition: a stronger signal admits more legs --------
            Case(
                id = "count_chosen_by_signal_strength",
                prices = listOf("100", "100", "99", "100", "99", "100", "99", "100", "99", "100", "100"),
                // close = 100 puts `strong` true, so the cap is 0.04 rather than 0.02
                expectedSides = buys(4),
                source =
                    """
                    STRATEGY count_chosen_by_signal_strength VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m
                    LET strong = x.close >= 100
                    LET cap = CASE WHEN strong THEN 0.04 ELSE 0.02 END
                    RULES
                      WHEN x.close >= 100 AND POSITION.x < cap
                      THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
            // -- each leg sized off volatility rather than a constant. The cap is set far above
            //    anything the expression can produce, so the leg COUNT is decided purely by how
            //    many times the signal re-arms; only the sizes vary. Tying the count to the cap
            //    instead would make it depend on the exact ATR each replay path computes while
            //    the indicator is still warming, which is a property of the tape, not the engine.
            Case(
                id = "each_leg_sized_by_atr",
                // the first six bars let atr(3) warm; the tail supplies three clean re-arms
                prices = listOf("100", "100", "100", "100", "100", "100", "99", "100", "99", "100", "99", "100", "100"),
                // four edges: the bar atr(3) first becomes non-null, then three re-arms
                expectedSides = buys(4),
                source =
                    """
                    STRATEGY each_leg_sized_by_atr VERSION 1
                    SYMBOLS x = BACKTEST:X EVERY 1m WARMUP 5 BARS
                    RULES
                      WHEN x.close >= 100 AND POSITION.x < 5 AND atr(x.candle, 3) IS NOT NULL
                      THEN BUY x SIZING 0.02 / (atr(x.candle, 3) + 1)
                           BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
                    """.trimIndent(),
            ),
        )

    @TestFactory
    fun `adaptive entry counts match ticks bars and live paper`(
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
                        startingBalance = BigDecimal("100000"),
                    )
                assertThat(result.backtest.trades.map { it.side })
                    .containsExactlyElementsOf(case.expectedSides)
            }
        }

    @Test
    fun `a STACK count cannot be an expression`(
        @TempDir tempDir: Path,
    ) {
        // The parser reads STACK's count as a NUMBER token and folds it with toIntOrNull, so
        // "as many layers as the balance allows" is not expressible as a stack. Use the capped
        // repeat-rule idiom above when the count has to be decided at runtime.
        val path = tempDir.resolve("dynamic_count.qkt")
        Files.writeString(
            path,
            """
            STRATEGY dynamic_count VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            LET n = 3
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 0.01 STACK n SPACING 1 ABOVE WITHIN 30m
                   BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
            """.trimIndent(),
        )
        assertThat(Dsl.parseFile(path)).isInstanceOf(ParseResult.Failure::class.java)
    }
}
