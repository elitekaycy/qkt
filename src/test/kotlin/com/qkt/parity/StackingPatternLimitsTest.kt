package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The edges of what a stacking strategy can express here, pinned so they cannot move silently.
 *
 * A strategy author reading the reference docs will reach for each of the shapes below, and each
 * one either does not exist or does not behave the way the neighbouring shapes do. Every case
 * here asserts the CURRENT behaviour, so the day one of them is implemented or fixed this test
 * fails and says so, rather than the limitation quietly outliving its documentation.
 */
class StackingPatternLimitsTest {
    private fun write(
        tempDir: Path,
        name: String,
        body: String,
    ): Path {
        val path = tempDir.resolve("$name.qkt")
        Files.writeString(
            path,
            """
            STRATEGY $name VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN $body
            """.trimIndent(),
        )
        return path
    }

    @Test
    fun `a bare stop with no target is refused at compile time`(
        @TempDir tempDir: Path,
    ) {
        // Deliberate: `docs/reference/dsl/bracket.md` calls STOP_LOSS and TAKE_PROFIT a complete
        // pair. A scalper who wants "stop only, exit on a signal" writes a far target plus a
        // rule-driven CLOSE -- which is what ScalpStackParityTest's stop-only case does.
        val path = write(tempDir, "bare_stop", "BUY x SIZING 0.01 BRACKET { STOP LOSS BY 5 }")
        assertThatThrownBy { GeneratedStrategyReplay.compile(path) }
            .hasMessageContaining("BRACKET requires both STOP LOSS and TAKE PROFIT")
    }

    @Test
    fun `the documented scale-out take-profit list does not parse`(
        @TempDir tempDir: Path,
    ) {
        // `docs/reference/dsl/bracket.md` documents a multi-leg take-profit with fractions --
        // "0.33 AT ...", a rule that the fractions sum to <= 1.0, and a gotcha about sums > 1.0
        // being a parse error. None of it is implemented: BracketAst carries a single takeProfit
        // and the parser has no branch for '{' after TAKE_PROFIT. Partial exits are reachable
        // only through RESIZE, which the same reference says not to combine with a BRACKET.
        val path =
            write(
                tempDir,
                "scale_out",
                """
                BUY x SIZING 0.30 BRACKET {
                  STOP_LOSS BY 50,
                  TAKE_PROFIT { 0.5 AT entry + 2, 0.5 AT entry + 4 }
                }
                """.trimIndent(),
            )
        val parsed = Dsl.parseFile(path)
        assertThat(parsed).isInstanceOf(ParseResult.Failure::class.java)
        assertThat((parsed as ParseResult.Failure).errors.first().message)
            .contains("expected child price")
    }

    @Test
    fun `a layer-list AT trigger priced below the seed rests as a limit and fills at its price`(
        @TempDir tempDir: Path,
    ) {
        // Regression: this layer used to be armed as a buy STOP below the market, which the
        // venue triggers immediately, so it filled on the first adverse tick and tick/bar
        // replay disagreed about when. OrderManager now rests a touch trigger that sits behind
        // the seed as a limit, matching what `STACK n SPACING d BELOW` compiles to.
        val path =
            write(
                tempDir,
                "at_below_seed",
                "BUY x STACK [ 0.01, 0.02 AT entry - 10 ] BRACKET { STOP LOSS BY 30, TAKE PROFIT BY 30 }",
            )
        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                closes = listOf("100", "100", "99", "95", "90", "90"),
                expectedTradeCount = 2,
            )
        assertThat(result.backtest.trades.map { it.price }).containsExactly("100", "90")
    }

    @Test
    fun `the same trigger priced above the seed is correct`(
        @TempDir tempDir: Path,
    ) {
        // The control for the case above: identical shape, opposite direction, exact fill at the
        // stated trigger across every replay path.
        val path =
            write(
                tempDir,
                "at_above_seed",
                "BUY x STACK [ 0.01, 0.02 AT entry + 10 ] BRACKET { STOP LOSS BY 30, TAKE PROFIT BY 30 }",
            )
        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                closes = listOf("100", "100", "101", "105", "110", "110"),
                expectedTradeCount = 2,
            )
        assertThat(result.backtest.trades.map { it.price }).containsExactly("100", "110")
    }
}
