package com.qkt.dsl.compile

import com.qkt.dsl.compile.CompileErrorLocator.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [CompileErrorLocator] narrowing: order-verb targets, section words inside a rule, odd rule lines. */
class CompileErrorLocatorSearchTest {
    private val source =
        listOf(
            "STRATEGY s VERSION 1",
            "RULES",
            "    WHEN eq.close > 0",
            "    THEN BUY eq SIZING 1",
            "    WHEN b.close > 0 THEN BUY b SIZING 1 BRACKET STOP 1",
            "    (b.close)",
        ).joinToString("\n")

    private fun locate(error: CompileError) = CompileErrorLocator.locate(source, error)

    @Test
    fun `a read-only series is found after the order verb inside its rule`() {
        assertThat(locate(CompileError("Series 'eq' is read-only", line = 3))).isEqualTo(Location(4, 14, 2))
    }

    @Test
    fun `a read-only series named nowhere falls back to its section keyword`() {
        assertThat(locate(CompileError("Series 'zz' is read-only"))).isEqualTo(Location(2, 1, 5))
    }

    @Test
    fun `a section error on a rule line points at the section word inside that rule`() {
        assertThat(locate(CompileError("BRACKET requires both", line = 5))).isEqualTo(Location(5, 42, 7))
    }

    @Test
    fun `a rule line that does not start with a word is pointed at with width one`() {
        assertThat(locate(CompileError("something odd", line = 6))).isEqualTo(Location(6, 5, 1))
    }

    @Test
    fun `a column without a line is not a position`() {
        assertThat(locate(CompileError("something odd", col = 4))).isEqualTo(Location(1, 1, 1))
    }
}
