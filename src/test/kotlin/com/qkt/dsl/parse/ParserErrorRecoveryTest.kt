package com.qkt.dsl.parse

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserErrorRecoveryTest {
    @Test
    fun `surfaces multiple errors per file`() {
        val src = Files.readString(Path.of("src/test/resources/dsl/syntax_errors.qkt"))
        val r = Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Failure
        assertThat(r.errors.size).isGreaterThanOrEqualTo(3)
        // distinct line numbers prove sync recovered, not just a cascade
        assertThat(
            r.errors
                .map { it.line }
                .distinct()
                .size,
        ).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `trailing content after the last recognized block is an error, not silently dropped`() {
        val src =
            """
            STRATEGY s VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULE
                WHEN btc.close > 100
                THEN BUY btc SIZING 1
            """.trimIndent()
        // `RULE` (typo for `RULES`) used to end parsing silently — the strategy
        // deployed "ok" with zero rules.
        val r = Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Failure
        assertThat(r.errors.first().message).contains("RULE")
        assertThat(r.errors.first().line).isEqualTo(6)
    }

    @Test
    fun `unsupported top-level block is an error`() {
        val src =
            """
            STRATEGY s VERSION 1
            WARMUP 200 BARS

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            """.trimIndent()
        val r = Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }

    @Test
    fun `portfolio with trailing content fails`() {
        val src =
            """
            PORTFOLIO p VERSION 1

            IMPORT "a.qkt" AS a

            WHEN 1 > 0 RUN a
            """.trimIndent()
        // Portfolio WHEN/RUN entries are only recognized inside a RULES block; a bare
        // trailing entry used to be silently discarded.
        val r = Parser(Lexer(src).tokenize()).parsePortfolio() as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }
}
