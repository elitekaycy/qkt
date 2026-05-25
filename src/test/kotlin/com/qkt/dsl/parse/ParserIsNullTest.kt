package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserIsNullTest {
    private fun expr(s: String): ExprAst {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    private fun errors(s: String): List<ParseError> {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy()
        return (r as ParseResult.Failure).errors
    }

    @Test
    fun `Ref IS NULL parses to IsNull non-negated`() {
        val e = expr("foo IS NULL")
        assertThat(e).isEqualTo(IsNull(Ref("foo"), negated = false))
    }

    @Test
    fun `Ref IS NOT NULL parses to IsNull negated`() {
        val e = expr("foo IS NOT NULL")
        assertThat(e).isEqualTo(IsNull(Ref("foo"), negated = true))
    }

    @Test
    fun `IS NULL binds tighter than AND`() {
        val e = expr("foo IS NOT NULL AND bar > 0") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.AND)
        assertThat(e.lhs).isEqualTo(IsNull(Ref("foo"), negated = true))
    }

    @Test
    fun `IS without trailing NULL is a parse error`() {
        val es = errors("foo IS NOT BAR")
        assertThat(es).isNotEmpty
        assertThat(es.first().message).contains("NULL")
    }

    @Test
    fun `IS NULL on an indicator call parses`() {
        val e = expr("EMA(g.close, 9) IS NULL")
        assertThat(e).isInstanceOf(IsNull::class.java)
        assertThat((e as IsNull).negated).isFalse
    }

    @Test
    fun `IS NULL on a numeric literal parses`() {
        val e = expr("1 IS NULL")
        assertThat(e).isEqualTo(IsNull(NumLit(BigDecimal("1")), negated = false))
    }
}
