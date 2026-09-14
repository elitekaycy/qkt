package com.qkt.dsl.parse

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** A section keyword after RULES used to spin the parser until the heap ran out (#1131). */
class ParserRulesOrderingTest {
    private fun parse(src: String): ParseResult<*> =
        assertTimeoutPreemptively(Duration.ofSeconds(5)) { Parser(Lexer(src).tokenize()).parseFile() }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "SYMBOLS\n    x = BACKTEST:BTCUSDT EVERY 1m",
            "LET a = 1",
            "PARAM a = 1",
            "DEFAULTS { SIZING 1 }",
            "SCHEDULE s EVERY 1m",
            "SEQUENCE seq\n    BUY x SIZING 1",
            "RULES\n    WHEN 1 > 0 THEN LOG \"y\"",
        ],
    )
    fun `a section keyword after RULES is one ordering error, not a hang`(trailing: String) {
        val src =
            """
            STRATEGY h VERSION 1
            RULES
                WHEN 1 > 0 THEN LOG "x"
            $trailing
            """.trimIndent()
        val r = parse(src) as ParseResult.Failure
        val keyword = trailing.substringBefore(' ').substringBefore('\n')
        assertThat(r.errors.map { it.message }).contains("$keyword must come before RULES")
        assertThat(r.errors.count { it.message == "$keyword must come before RULES" }).isEqualTo(1)
        assertThat(r.errors.first().line).isEqualTo(4)
    }

    @Test
    fun `the DSL reference ordering example fails cleanly`() {
        val src =
            """
            STRATEGY h VERSION 1
            RULES
                WHEN 1 > 0 THEN LOG "x"
            SYMBOLS
            """.trimIndent()
        val r = parse(src) as ParseResult.Failure
        assertThat(r.errors).hasSize(1)
        assertThat(r.errors.single().message).isEqualTo("SYMBOLS must come before RULES")
    }

    @Test
    fun `a stray non-section token inside RULES still reports and recovers`() {
        val src =
            """
            STRATEGY h VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                BUY x SIZING 1
                WHEN x.close > 1 THEN BUY x SIZING 1
            """.trimIndent()
        val r = parse(src) as ParseResult.Failure
        assertThat(r.errors.map { it.message }).anyMatch { it.startsWith("expected WHEN or FOR EACH in RULES") }
    }

    @Test
    fun `a portfolio with a section keyword after RULES also terminates`() {
        val src =
            """
            PORTFOLIO p VERSION 1
            RULES
                WHEN 1 > 0 THEN LOG "x"
            SYMBOLS
            """.trimIndent()
        assertThat(parse(src)).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `a well-formed strategy still parses`() {
        val src =
            """
            STRATEGY h VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            LET a = 1
            RULES
                WHEN x.close > a THEN BUY x SIZING 1
                FOR EACH s IN [x] DO
                    WHEN s.close < a THEN SELL s SIZING 1
            """.trimIndent()
        assertThat(parse(src)).isInstanceOf(ParseResult.Success::class.java)
    }
}
