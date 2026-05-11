package com.qkt.dsl.parse

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExprIndicatorTest {
    private fun expr(s: String): ExprAst {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `parses indicator call with stream field arg`() {
        val e = expr("EMA(close, 9)") as IndicatorCall
        assertThat(e.name).isEqualTo("EMA")
        assertThat(e.args).hasSize(2)
    }

    @Test
    fun `parses nested indicator calls`() {
        val e = expr("RSI(EMA(close, 9), 14)") as IndicatorCall
        assertThat(e.args[0]).isInstanceOf(IndicatorCall::class.java)
    }

    @Test
    fun `parses stream field reference`() {
        val e = expr("btc.close") as StreamFieldRef
        assertThat(e.stream).isEqualTo("btc")
        assertThat(e.field).isEqualTo("close")
    }

    @Test
    fun `zero-arg call`() {
        val e = expr("rand()") as IndicatorCall
        assertThat(e.name).isEqualTo("rand")
        assertThat(e.args).isEmpty()
    }
}
