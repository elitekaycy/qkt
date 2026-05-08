package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExprBooleanTest {
    private fun expr(s: String): ExprAst {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `AND is left-associative`() {
        val e = expr("a AND b AND c") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.AND)
        assertThat((e.lhs as BinaryOp).op).isEqualTo(BinOp.AND)
    }

    @Test
    fun `OR has lower precedence than AND`() {
        val e = expr("a OR b AND c") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.OR)
        assertThat((e.rhs as BinaryOp).op).isEqualTo(BinOp.AND)
    }

    @Test
    fun `NOT binds tighter than AND`() {
        val e = expr("NOT a AND b") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.AND)
        assertThat((e.lhs as UnaryOp).op).isEqualTo(UnOp.NOT)
    }
}
