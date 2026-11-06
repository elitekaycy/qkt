package com.qkt.dsl.compile

import com.qkt.dsl.compile.CompileErrorLocator.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [CompileErrorLocator] fallbacks: explicit positions, rule lines, section keywords and the file start. */
class CompileErrorLocatorFallbackTest {
    private val source =
        """
        STRATEGY s VERSION 1
        SYMBOLS
            b = BACKTEST:BTCUSDT EVERY 1m
        RULES
            WHEN b.close > 0
            THEN BUY b SIZING 1
            WHEN b.open > 0 THEN CLOSE b
        """.trimIndent()

    @Test
    fun `an error that carries a column is reported where it says`() {
        assertThat(
            CompileErrorLocator.locate(source, CompileError("anything", line = 2, col = 5)),
        ).isEqualTo(Location(2, 5, 1))
    }

    @Test
    fun `an unmatched message on a rule line points at the rule's WHEN`() {
        assertThat(
            CompileErrorLocator.locate(source, CompileError("something odd", line = 5)),
        ).isEqualTo(Location(5, 5, 4))
    }

    @Test
    fun `a named identifier is found inside its rule`() {
        val error = CompileError("Unknown stream alias: b", line = 7)

        assertThat(CompileErrorLocator.locate(source, error)).isEqualTo(Location(7, 10, 1))
    }

    @Test
    fun `a rule line past the end of the file falls back to a whole-file search`() {
        val error = CompileError("Unknown stream alias: b", line = 99)

        assertThat(CompileErrorLocator.locate(source, error)).isEqualTo(Location(3, 5, 1))
    }

    @Test
    fun `a section rule without an identifier points at the section keyword`() {
        assertThat(
            CompileErrorLocator.locate(source, CompileError("BRACKET requires both")),
        ).isEqualTo(Location(1, 1, 1))
        val withLet = "STRATEGY s VERSION 1\nLET a = 1\nLET a = 2\n"
        assertThat(
            CompileErrorLocator.locate(withLet, CompileError("Duplicate LET name in: [a, a]")),
        ).isEqualTo(Location(2, 1, 3))
    }

    @Test
    fun `an unknown message with no line points at the start of the file`() {
        assertThat(CompileErrorLocator.locate(source, CompileError("mystery"))).isEqualTo(Location(1, 1, 1))
    }
}
