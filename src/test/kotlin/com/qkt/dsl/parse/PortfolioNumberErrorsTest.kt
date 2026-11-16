package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A number token the lexer reads loosely (`1e`) is refused by name where a portfolio needs a number. */
class PortfolioNumberErrorsTest {
    private fun errors(src: String): List<String> = (Dsl.parseAny(src) as ParseResult.Failure).errors.map { it.message }

    @Test
    fun `a malformed CAPITAL number is named`() {
        assertThat(errors("PORTFOLIO p VERSION 1 CAPITAL 1e\n"))
            .anyMatch { it.startsWith("CAPITAL must be a number, got '1e'") }
    }

    @Test
    fun `a malformed allocation weight is named`() {
        val src =
            """
            PORTFOLIO p VERSION 1 CAPITAL 1000
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'a.qkt' AS a
            REGIMES
                NAME r
                STATE flat DEFAULT
            ALLOCATE
                METHOD regime_weighted
                flat -> a 1e
            RULES
                RUN a
            """.trimIndent()

        assertThat(errors(src)).anyMatch { it.startsWith("weight must be a number, got") }
    }
}
