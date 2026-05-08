package com.qkt.dsl.parse

import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.StrategyAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserDefaultsTest {
    private fun parse(s: String): StrategyAst =
        (Parser(Lexer(s).tokenize()).parseStrategy() as ParseResult.Success).value

    @Test
    fun `defaults block parses all clauses`() {
        val ast =
            parse(
                """
                STRATEGY s VERSION 1
                DEFAULTS {
                    SIZING       = RISK 0.01
                    STOP_LOSS    = BY 0.5
                    TAKE_PROFIT  = RR 3
                    TIF          = GTC
                    ORDER_TYPE   = MARKET
                }
                """.trimIndent(),
            )
        val d = ast.defaults!!
        assertThat(d.sizing).isInstanceOf(SizeRiskFrac::class.java)
        assertThat(d.stopLoss).isInstanceOf(ChildBy::class.java)
        assertThat(d.takeProfit).isInstanceOf(ChildRr::class.java)
        assertThat(d.tif).isEqualTo(Gtc)
        assertThat(d.orderType).isNotNull
    }

    @Test
    fun `empty defaults block`() {
        val ast = parse("STRATEGY s VERSION 1\nDEFAULTS { }")
        assertThat(ast.defaults).isNotNull
    }
}
