package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Inputs the parser must reject with a located message instead of crashing or accepting. */
class ParserRejectedInputTest {
    private fun errors(src: String): List<String> = (Dsl.parse(src) as ParseResult.Failure).errors.map { it.message }

    @Test
    fun `a hub scope longer than the store allows is rejected`() {
        val scope = "A".repeat(65)
        val msgs =
            errors(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    c = HUB:cal.us_high.$scope EVERY 1d
                """.trimIndent(),
            )

        assertThat(msgs).anyMatch { it.startsWith("hub scope '$scope' must be upper case") }
    }

    @Test
    fun `a synchronize timeout too large for a long is rejected`() {
        val msgs =
            errors(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    a = EXNESS:XAUUSD EVERY 1h,
                    b = EXNESS:XAGUSD EVERY 1h
                    SYNCHRONIZE a b WITHIN 99999999999999999999m
                RULES
                    WHEN a.close > b.close THEN BUY a SIZING 0.1
                """.trimIndent(),
            )

        assertThat(msgs).anyMatch { it.startsWith("invalid duration literal '99999999999999999999m'") }
    }

    @Test
    fun `a number after OPEN_ORDERS dot is not a field name`() {
        val msgs = errors("STRATEGY s VERSION 1\nLET x = OPEN_ORDERS.5")

        assertThat(msgs).anyMatch { it.startsWith("expected field name, got '5'") }
    }
}
