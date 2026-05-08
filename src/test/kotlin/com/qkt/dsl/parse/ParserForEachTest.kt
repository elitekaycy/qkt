package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserForEachTest {
    private fun parse(s: String): StrategyAst =
        (Parser(Lexer(s).tokenize()).parseStrategy() as ParseResult.Success).value

    @Test
    fun `FOR EACH expands to one rule per alias`() {
        val ast =
            parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    btc  = BACKTEST:BTCUSDT EVERY 1m,
                    gold = BACKTEST:XAUUSD  EVERY 1m
                RULES
                    FOR EACH s IN [btc, gold] DO
                        WHEN s.close > 0 THEN BUY s
                """.trimIndent(),
            )
        assertThat(ast.rules).hasSize(2)
        val first = ast.rules[0] as WhenThen
        val second = ast.rules[1] as WhenThen
        // first rule should reference btc
        assertThat(((first.cond as CmpOp).lhs as StreamFieldRef).stream).isEqualTo("btc")
        assertThat((first.action as Buy).stream).isEqualTo("btc")
        // second rule should reference gold
        assertThat(((second.cond as CmpOp).lhs as StreamFieldRef).stream).isEqualTo("gold")
        assertThat((second.action as Buy).stream).isEqualTo("gold")
    }
}
