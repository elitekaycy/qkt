package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExprArithmeticTest {
    private fun expr(s: String): ExprAst {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `addition is left-associative`() {
        val e = expr("1 + 2 + 3") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.ADD)
        assertThat((e.lhs as BinaryOp).op).isEqualTo(BinOp.ADD)
    }

    @Test
    fun `multiplication binds tighter than addition`() {
        val e = expr("1 + 2 * 3") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.ADD)
        assertThat((e.rhs as BinaryOp).op).isEqualTo(BinOp.MUL)
    }

    @Test
    fun `parentheses override precedence`() {
        val e = expr("(1 + 2) * 3") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.MUL)
        assertThat((e.lhs as BinaryOp).op).isEqualTo(BinOp.ADD)
    }

    @Test
    fun `unary minus negates`() {
        val e = expr("-5") as UnaryOp
        assertThat(e.op).isEqualTo(UnOp.NEG)
    }
}
