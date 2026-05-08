package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserRulesTest {
    private fun parse(s: String): StrategyAst =
        (Parser(Lexer(s).tokenize()).parseStrategy() as ParseResult.Success).value

    @Test
    fun `rules with single WHEN-THEN`() {
        val ast =
            parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                    WHEN btc.close > 100 THEN BUY btc
                """.trimIndent(),
            )
        assertThat(ast.rules).hasSize(1)
        val r = ast.rules[0] as WhenThen
        assertThat(r.cond).isInstanceOf(CmpOp::class.java)
        assertThat(r.action).isInstanceOf(Buy::class.java)
    }

    @Test
    fun `rules with multiple WHEN-THEN`() {
        val ast =
            parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                    WHEN btc.close > 100 THEN BUY btc
                    WHEN ACCOUNT.equity < 9500 THEN CLOSE_ALL
                """.trimIndent(),
            )
        assertThat(ast.rules).hasSize(2)
        assertThat((ast.rules[1] as WhenThen).action).isEqualTo(CloseAll)
    }
}
