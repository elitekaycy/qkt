package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExprComparisonTest {
    private fun expr(s: String): ExprAst {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `greater than over arithmetic`() {
        val e = expr("1 + 2 > 3") as CmpOp
        assertThat(e.op).isEqualTo(Cmp.GT)
        assertThat((e.lhs as BinaryOp).op).isEqualTo(BinOp.ADD)
    }

    @Test
    fun `equality with double-equals`() {
        val e = expr("a == b") as CmpOp
        assertThat(e.op).isEqualTo(Cmp.EQ)
    }

    @Test
    fun `not equal`() {
        val e = expr("a != b") as CmpOp
        assertThat(e.op).isEqualTo(Cmp.NE)
    }

    @Test
    fun `less-than-or-equal`() {
        val e = expr("a <= b") as CmpOp
        assertThat(e.op).isEqualTo(Cmp.LE)
    }

    @Test
    fun `greater-than-or-equal`() {
        val e = expr("a >= b") as CmpOp
        assertThat(e.op).isEqualTo(Cmp.GE)
    }
}
