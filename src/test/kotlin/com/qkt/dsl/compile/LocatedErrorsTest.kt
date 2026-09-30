package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseError
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Inputs that used to escape `qkt parse` as bare exceptions (or crash at first evaluation) each
 * become one error with a position: parse errors carry the token, compile errors the rule.
 */
class LocatedErrorsTest {
    private fun parseErrors(src: String): List<ParseError> = (Dsl.parseAny(src) as ParseResult.Failure).errors

    private fun compileError(src: String): CompileError {
        val parsed = Dsl.parseAny(src) as ParseResult.Success
        val ast = (parsed.value as ParsedFile.StrategyFile).ast
        return org.assertj.core.api.Assertions
            .catchThrowableOfType(CompileError::class.java) { AstCompiler().compile(ast) }
    }

    @Test
    fun `SIZING with a non-literal PCT RISK is a parse error at the expression`() {
        val errors =
            parseErrors(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                PARAM r = 1
                RULES
                    WHEN gold.close > 0
                    THEN BUY gold SIZING r PCT RISK
                """.trimIndent(),
            )

        assertThat(errors).hasSize(1)
        assertThat(errors.single().message).contains("SIZING N PCT RISK requires a numeric literal")
        assertThat(errors.single().line).isEqualTo(7)
        assertThat(errors.single().col).isEqualTo(26)
    }

    @Test
    fun `a duplicate PARAM name is a parse error at the second declaration`() {
        val errors =
            parseErrors(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                PARAM n = 1
                PARAM n = 2
                RULES
                    WHEN gold.close > n THEN BUY gold SIZING 1
                """.trimIndent(),
            )

        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isEqualTo(ParseError(5, 7, "Duplicate PARAM 'n'"))
    }

    @Test
    fun `EVERY HOUR AT with a minute past 59 is a parse error at the minute`() {
        val errors =
            parseErrors(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                SCHEDULE
                    EVERY HOUR AT :75 THEN CLOSE_ALL
                RULES
                    WHEN gold.close > 0 THEN BUY gold SIZING 1
                """.trimIndent(),
            )

        assertThat(errors).hasSize(1)
        assertThat(errors.single()).isEqualTo(ParseError(5, 20, "EVERY HOUR AT minute must be 0-59, got 75"))
    }

    @Test
    fun `a SEQUENCE with one stage or nine stages is a parse error at its name`() {
        fun sequence(stages: Int) =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            SEQUENCE setup ON gold {
            ${(1..stages).joinToString("\n") { "                STAGE s$it: gold.close > $it" }}
            }
            RULES
                WHEN SEQUENCE.setup.complete THEN BUY gold SIZING 1
            """.trimIndent()

        val one = parseErrors(sequence(1)).single()
        val nine = parseErrors(sequence(9)).single()

        assertThat(one).isEqualTo(ParseError(4, 10, "SEQUENCE 'setup' must declare 2-8 stages, got 1"))
        assertThat(nine).isEqualTo(ParseError(4, 10, "SEQUENCE 'setup' must declare 2-8 stages, got 9"))
    }

    @Test
    fun `BUY on a HUB dataset alias is a compile error tagged with the rule`() {
        val error =
            compileError(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    cal = HUB:cal.high_impact.USD EVERY 1d
                RULES
                    WHEN cal.surprise > 0
                    THEN BUY cal SIZING 0.01
                """.trimIndent(),
            )

        assertThat(error.message).startsWith("Series 'cal' is read-only")
        assertThat(error.line).isEqualTo(5)
    }

    @Test
    fun `a LET that refers to itself through another LET is a compile error naming the cycle`() {
        val error =
            compileError(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                LET a = b + 1, b = a * 2
                RULES
                    WHEN a > 0 THEN BUY gold SIZING 1
                """.trimIndent(),
            )

        assertThat(
            error.message,
        ).isEqualTo("LET 'a' refers to itself (a -> b -> a); a LET cannot depend on its own value")
        assertThat(error.line).isNull()
    }

    @Test
    fun `a LET that refers directly to itself is rejected the same way`() {
        val error =
            compileError(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                LET a = a + 1
                RULES
                    WHEN a > 0 THEN BUY gold SIZING 1
                """.trimIndent(),
            )

        assertThat(error.message).startsWith("LET 'a' refers to itself (a -> a)")
    }

    @Test
    fun `an undeclared alias anywhere in a rule fails at compile time even when another alias is the rule's stream`() {
        val error =
            compileError(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                    WHEN xyz.close > 0 THEN BUY btc SIZING 0.1
                """.trimIndent(),
            )

        assertThat(error.message).isEqualTo("Unknown stream alias: xyz")
        assertThat(error.line).isEqualTo(5)
    }

    @Test
    fun `a FOR EACH expansion is tagged with the macro's line`() {
        val error =
            compileError(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    a = BACKTEST:AAA EVERY 1m
                    b = BACKTEST:BBB EVERY 1m
                RULES
                    FOR EACH s IN [a, b] DO
                        WHEN bogus(s.candle, 14) > 0 THEN BUY s SIZING 1
                """.trimIndent(),
            )

        assertThat(error.message).isEqualTo("Unknown indicator: bogus")
        assertThat(error.line).isEqualTo(6)
    }

    @Test
    fun `a compile failure keeps the original exception as its cause`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN sma(gold.candle) > 0 THEN BUY gold SIZING 1
            """.trimIndent()
        val ast = ((Dsl.parseAny(src) as ParseResult.Success).value as ParsedFile.StrategyFile).ast

        assertThatThrownBy { AstCompiler().compile(ast) }
            .isInstanceOf(CompileError::class.java)
            .hasMessageContaining("Indicator sma expects")
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
    }
}
