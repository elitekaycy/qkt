package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** CALENDAR_WINDOW and SESSION_WINDOW take four integers, each checked against its own range. */
class ClockWindowArgumentsTest {
    private fun errors(cond: String): List<String> {
        val source =
            "STRATEGY s VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\nRULES\n    WHEN $cond THEN CLOSE b\n"
        return when (val r = Dsl.parse(source)) {
            is ParseResult.Failure -> r.errors.map { it.message }
            is ParseResult.Success -> emptyList()
        }
    }

    @Test
    fun `every calendar month and day argument is range-checked`() {
        val range = "CALENDAR_WINDOW month must be 1-12 and day 1-31"
        for (args in listOf("13, 1, 2, 1", "1, 0, 2, 1", "1, 1, 13, 1", "1, 1, 2, 32")) {
            assertThat(errors("CALENDAR_WINDOW($args)")).describedAs(args).contains(range)
        }
        assertThat(errors("CALENDAR_WINDOW(1, 1, 2, 1)")).isEmpty()
        assertThat(
            errors("CALENDAR_WINDOW(1.5, 1, 2, 1)"),
        ).anyMatch { it.startsWith("CALENDAR_WINDOW expects 4 integer") }
    }

    @Test
    fun `every session hour and minute argument is range-checked`() {
        val range = "SESSION_WINDOW hour must be 0-23 and minute 0-59"
        for (args in listOf("24, 0, 1, 0", "0, 60, 1, 0", "0, 0, 24, 0", "0, 0, 1, 60")) {
            assertThat(errors("SESSION_WINDOW($args)")).describedAs(args).contains(range)
        }
        assertThat(errors("SESSION_WINDOW(8, 0, 16, 30)")).isEmpty()
        assertThat(errors("SESSION_WINDOW(8, 0, 16)")).anyMatch { it.startsWith("SESSION_WINDOW expects 4 integer") }
        assertThat(
            errors("SESSION_WINDOW(8, 0.5, 16, 0)"),
        ).anyMatch { it.startsWith("SESSION_WINDOW expects 4 integer") }
    }

    @Test
    fun `a NOW field must be a name`() {
        assertThat(errors("NOW.5 > 0")).contains("expected NOW field name, got '5'")
    }
}
