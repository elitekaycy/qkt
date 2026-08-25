package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Repeated `LET` LINES are the documented form — `docs/reference/dsl/let-defaults.md`
 * and the session-range example in `docs/reference/dsl/indicators.md` both declare two.
 * `parseLet` consumes one `LET` keyword plus its comma-separated bindings, so the caller
 * must loop (as it does for `PARAM`); a single `if` stopped after the first line and
 * every later `LET` surfaced as "unexpected token after the last recognized block".
 */
class ParserRepeatedLetTest {
    private fun parse(s: String): ParseResult<StrategyAst> = Parser(Lexer(s).tokenize()).parseStrategy()

    private fun ok(s: String): StrategyAst = (parse(s) as ParseResult.Success).value

    @Test
    fun `repeated LET lines all bind`() {
        val ast =
            ok(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    g = BACKTEST:XAUUSD EVERY 5m
                LET fast = ema(g.close, 9)
                LET slow = ema(g.close, 21)
                RULES
                    WHEN fast > slow THEN LOG "up"
                """.trimIndent(),
            )

        assertThat(ast.lets.map { it.name }).containsExactly("fast", "slow")
    }

    @Test
    fun `repeated LET lines coexist with PARAM declarations`() {
        val ast =
            ok(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    g = BACKTEST:XAUUSD EVERY 5m
                PARAM slAtr = 1.5
                PARAM tpAtr = 3.0
                LET hi = session_range_high(g.candle, 13, 0, 13, 30)
                LET lo = session_range_low(g.candle, 13, 0, 13, 30)
                RULES
                    WHEN g.close > hi THEN LOG "break"
                """.trimIndent(),
            )

        assertThat(ast.params.map { it.name }).containsExactly("slAtr", "tpAtr")
        assertThat(ast.lets.map { it.name }).containsExactly("hi", "lo")
    }

    @Test
    fun `the comma form still binds every name`() {
        val ast =
            ok(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    g = BACKTEST:XAUUSD EVERY 5m
                LET fast = ema(g.close, 9), slow = ema(g.close, 21)
                RULES
                    WHEN fast > slow THEN LOG "up"
                """.trimIndent(),
            )

        assertThat(ast.lets.map { it.name }).containsExactly("fast", "slow")
    }
}
