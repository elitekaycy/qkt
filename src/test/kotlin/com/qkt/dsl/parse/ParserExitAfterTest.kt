package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExitAfterTest {
    private fun parse(actionDsl: String): ParseResult<StrategyAst> =
        Parser(
            Lexer(
                """
                STRATEGY x VERSION 1
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                    WHEN btc.close > 0 THEN $actionDsl
                """.trimIndent(),
            ).tokenize(),
        ).parseStrategy()

    private fun buy(actionDsl: String): Buy {
        val r = parse(actionDsl) as ParseResult.Success
        return (r.value.rules[0] as WhenThen).action as Buy
    }

    @Test
    fun `EXIT AFTER parses its duration in milliseconds`() {
        assertThat(buy("BUY btc SIZING 0.01 EXIT AFTER 4m").opts.exitAfter?.millis).isEqualTo(240_000L)
    }

    @Test
    fun `EXIT AFTER is order-independent among the other clauses`() {
        val opts =
            buy(
                """BUY btc EXIT AFTER 90s SIZING 0.01
                STACK_AT MFE >= 5 WITHIN 4m SIZING 0.05 BRACKET { STOP LOSS BY 15, TAKE PROFIT BY 1 }""",
            ).opts
        assertThat(opts.exitAfter?.millis).isEqualTo(90_000L)
        assertThat(opts.stackAts).hasSize(1)
    }

    @Test
    fun `an action without EXIT AFTER has no timed exit`() {
        assertThat(buy("BUY btc SIZING 0.01").opts.exitAfter).isNull()
    }

    @Test
    fun `EXIT without AFTER fails to parse`() {
        assertThat(parse("BUY btc SIZING 0.01 EXIT 4m")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `EXIT AFTER needs a duration literal`() {
        assertThat(parse("BUY btc SIZING 0.01 EXIT AFTER 240")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `a zero EXIT AFTER duration fails to parse`() {
        assertThat(parse("BUY btc SIZING 0.01 EXIT AFTER 0s")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `a duplicate EXIT AFTER fails to parse`() {
        assertThat(parse("BUY btc SIZING 0.01 EXIT AFTER 1m EXIT AFTER 2m"))
            .isInstanceOf(ParseResult.Failure::class.java)
    }
}
