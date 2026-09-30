package com.qkt.dsl.compile

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompileErrorLocatorTest {
    private val source =
        """
        STRATEGY s VERSION 1

        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 1m
            cal = HUB:cal.high_impact.USD EVERY 1d

        LET fast = sma(gold.candle, 5)

        RULES
            WHEN cal.surprise > 0
            THEN BUY cal SIZING 0.01
            WHEN bogus(gold.candle, 14) > 100 AND POSITION.gold = 0
            THEN BUY gold SIZING 1
        """.trimIndent()

    @Test
    fun `an identifier named in the message is found inside the tagged rule`() {
        val at = CompileErrorLocator.locate(source, CompileError("Unknown indicator: bogus", line = 12))

        assertThat(at).isEqualTo(CompileErrorLocator.Location(line = 12, col = 10, width = 5))
    }

    @Test
    fun `the search is bounded to the tagged rule so an earlier rule's alias is not matched`() {
        // `gold` appears on lines 4, 7 and 12; the rule tagged on line 10 does not mention it.
        val at = CompileErrorLocator.locate(source, CompileError("Unknown stream alias: gold", line = 10))

        assertThat(at.line).isEqualTo(10)
        assertThat(at.col).isEqualTo(5)
        assertThat(at.width).isEqualTo("WHEN".length)
    }

    @Test
    fun `a read-only series points at the order that targets it, not the condition that reads it`() {
        val at = CompileErrorLocator.locate(source, CompileError("Series 'cal' is read-only — no", line = 10))

        assertThat(at).isEqualTo(CompileErrorLocator.Location(line = 11, col = 14, width = 3))
    }

    @Test
    fun `a declaration error without a rule is found anywhere in the file`() {
        val at = CompileErrorLocator.locate(source, CompileError("LET 'fast' refers to itself (fast -> fast)"))

        assertThat(at).isEqualTo(CompileErrorLocator.Location(line = 7, col = 5, width = 4))
    }

    @Test
    fun `a sectional error without an identifier lands on its section keyword`() {
        val at = CompileErrorLocator.locate(source, CompileError("BRACKET requires both STOP LOSS and TAKE PROFIT"))

        // No BRACKET in this file: falls through to 1:1.
        assertThat(at).isEqualTo(CompileErrorLocator.Location(1, 1, 1))

        val withBracket = source.replace("SIZING 1", "SIZING 1 BRACKET { STOP LOSS BY 1 PCT }")
        val found =
            CompileErrorLocator.locate(
                withBracket,
                CompileError("BRACKET requires both STOP LOSS and TAKE PROFIT"),
            )
        assertThat(found).isEqualTo(CompileErrorLocator.Location(line = 13, col = 28, width = 7))
    }

    @Test
    fun `an unrecognised message with no rule falls back to one one`() {
        val at = CompileErrorLocator.locate(source, CompileError("something else went wrong"))

        assertThat(at).isEqualTo(CompileErrorLocator.Location(1, 1, 1))
    }

    @Test
    fun `an error that already carries a column passes through`() {
        val at = CompileErrorLocator.locate(source, CompileError("x", line = 3, col = 4))

        assertThat(at).isEqualTo(CompileErrorLocator.Location(3, 4, 1))
    }
}
