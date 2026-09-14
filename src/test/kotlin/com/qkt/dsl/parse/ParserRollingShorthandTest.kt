package com.qkt.dsl.parse

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `sum/mean/avg/count(<expr>, N)` desugar to the existing `SINCE T-N` aggregate (#1130). */
class ParserRollingShorthandTest {
    private fun parse(let: String) = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $let").tokenize()).parseStrategy()

    private fun expr(let: String): ExprAst = (parse(let) as ParseResult.Success).value.lets[0].expr

    @Test
    fun `sum and mean with a window equal their SINCE T-N forms`() {
        assertThat(expr("sum(btc.close, 20)")).isEqualTo(expr("sum(btc.close) SINCE T-20"))
        assertThat(expr("mean(btc.close, 5)")).isEqualTo(expr("mean(btc.close) SINCE T-5"))
    }

    @Test
    fun `avg is mean over the window`() {
        val e = expr("avg(btc.close, 14)") as Aggregate
        assertThat(e.fn).isEqualTo(AggFn.MEAN)
        assertThat(e.window).isEqualTo(SinceTPast(14))
        assertThat(e.series).isEqualTo(StreamFieldRef("btc", "close"))
    }

    @Test
    fun `count sums a 1-or-0 case over the window`() {
        val e = expr("count(btc.close > btc.open, 10)") as Aggregate
        assertThat(e.fn).isEqualTo(AggFn.SUM)
        assertThat(e.window).isEqualTo(SinceTPast(10))
        val c = e.series as CaseWhen
        assertThat(c.branches.single().second).isEqualTo(NumLit(java.math.BigDecimal.ONE))
        assertThat(c.elseExpr).isEqualTo(NumLit(java.math.BigDecimal.ZERO))
    }

    @Test
    fun `two-argument max and min stay the scalar functions`() {
        assertThat(expr("max(btc.close, 20)")).isInstanceOf(FuncCall::class.java)
        assertThat(expr("min(btc.close, 20)")).isInstanceOf(FuncCall::class.java)
    }

    @Test
    fun `the window must be a positive integer literal`() {
        for (bad in listOf(
            "sum(btc.close, 0)",
            "sum(btc.close, 2.5)",
            "mean(btc.close, n)",
            "avg(btc.close, 0)",
            "count(btc.close > 1, -3)",
            "avg(btc.close)",
        )) {
            assertThat(parse(bad)).describedAs(bad).isInstanceOf(ParseResult.Failure::class.java)
        }
    }
}
