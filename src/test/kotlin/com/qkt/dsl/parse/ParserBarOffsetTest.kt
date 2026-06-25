package com.qkt.dsl.parse

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserBarOffsetTest {
    private fun letExpr(rhs: String): ExprAst {
        val src = "STRATEGY x VERSION 1\nLET v = $rhs"
        val res = Parser(Lexer(src).tokenize()).parseStrategy()
        return (res as ParseResult.Success).value.lets[0].expr
    }

    @Test
    fun `stream field bracket N compiles to a lag indicator call`() {
        val e = letExpr("btc.close[20]")
        assertThat(e).isInstanceOf(IndicatorCall::class.java)
        e as IndicatorCall
        assertThat(e.name).isEqualTo("LAG")
        assertThat(e.args).hasSize(2)
        assertThat((e.args[0] as StreamFieldRef).field).isEqualTo("close")
        assertThat((e.args[1] as NumLit).value).isEqualByComparingTo("20")
    }

    @Test
    fun `bracket zero is the bare current-bar field`() {
        assertThat(letExpr("btc.close[0]")).isInstanceOf(StreamFieldRef::class.java)
    }

    @Test
    fun `works on any candle field`() {
        val e = letExpr("btc.high[2]") as IndicatorCall
        assertThat(e.name).isEqualTo("LAG")
        assertThat((e.args[0] as StreamFieldRef).field).isEqualTo("high")
    }

    @Test
    fun `negative offset is a parse error`() {
        val src = "STRATEGY x VERSION 1\nLET v = btc.close[-1]"
        val res = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(res).isInstanceOf(ParseResult.Failure::class.java)
    }
}
