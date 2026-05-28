package com.qkt.dsl.parse

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.IndicatorCall
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExprFuncCallTest {
    private fun expr(s: String): ExprAst {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `lowercase abs routes to FuncCall`() {
        val e = expr("abs(-5)") as FuncCall
        assertThat(e.name).isEqualTo("ABS")
        assertThat(e.args).hasSize(1)
    }

    @Test
    fun `uppercase ABS routes to FuncCall`() {
        val e = expr("ABS(-5)") as FuncCall
        assertThat(e.name).isEqualTo("ABS")
    }

    @Test
    fun `sqrt routes to FuncCall`() {
        val e = expr("sqrt(16)") as FuncCall
        assertThat(e.name).isEqualTo("SQRT")
    }

    @Test
    fun `log routes to FuncCall`() {
        val e = expr("log(2)") as FuncCall
        assertThat(e.name).isEqualTo("LOG")
    }

    @Test
    fun `exp routes to FuncCall`() {
        val e = expr("exp(1)") as FuncCall
        assertThat(e.name).isEqualTo("EXP")
    }

    @Test
    fun `pow routes to FuncCall`() {
        val e = expr("pow(2, 10)") as FuncCall
        assertThat(e.name).isEqualTo("POW")
        assertThat(e.args).hasSize(2)
    }

    @Test
    fun `unknown name still routes to IndicatorCall (existing behavior)`() {
        val e = expr("EMA(close, 9)") as IndicatorCall
        assertThat(e.name).isEqualTo("EMA")
    }

    @Test
    fun `mixed-case math fn works`() {
        val e = expr("Sqrt(4)") as FuncCall
        assertThat(e.name).isEqualTo("SQRT")
    }

    @Test
    fun `math fn can be nested inside an indicator call`() {
        // log(close) is a FuncCall; EMA(<FuncCall>, ...) keeps EMA as IndicatorCall.
        val e = expr("EMA(log(close), 9)") as IndicatorCall
        assertThat(e.name).isEqualTo("EMA")
        assertThat(e.args[0]).isInstanceOf(FuncCall::class.java)
    }
}
