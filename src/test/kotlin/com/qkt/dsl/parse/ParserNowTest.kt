package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserNowTest {
    private fun parseExprInLet(s: String): ExprAst {
        val r =
            Parser(Lexer("STRATEGY x VERSION 1\nLET v = $s").tokenize())
                .parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `NOW dot hour_utc parses to NowAccessor`() {
        val e = parseExprInLet("NOW.hour_utc") as NowAccessor
        assertThat(e.field).isEqualTo(NowField.HOUR_UTC)
    }

    @Test
    fun `NOW field names are case-insensitive`() {
        val e = parseExprInLet("now.HOUR_UTC") as NowAccessor
        assertThat(e.field).isEqualTo(NowField.HOUR_UTC)
    }

    @Test
    fun `each NOW field parses`() {
        assertThat((parseExprInLet("NOW.hour_utc") as NowAccessor).field).isEqualTo(NowField.HOUR_UTC)
        assertThat((parseExprInLet("NOW.minute_utc") as NowAccessor).field).isEqualTo(NowField.MINUTE_UTC)
        assertThat((parseExprInLet("NOW.weekday") as NowAccessor).field).isEqualTo(NowField.WEEKDAY)
        assertThat((parseExprInLet("NOW.date_utc") as NowAccessor).field).isEqualTo(NowField.DATE_UTC)
        assertThat((parseExprInLet("NOW.epoch_ms") as NowAccessor).field).isEqualTo(NowField.EPOCH_MS)
    }

    @Test
    fun `bare NOW parses as NowAccessor with EPOCH_MS`() {
        val e = parseExprInLet("NOW") as NowAccessor
        assertThat(e.field).isEqualTo(NowField.EPOCH_MS)
    }

    @Test
    fun `NOW plus 10m parses as BinaryOp add`() {
        val e = parseExprInLet("NOW + 10m") as BinaryOp
        assertThat(e.op).isEqualTo(com.qkt.dsl.ast.BinOp.ADD)
        assertThat(e.lhs).isInstanceOf(NowAccessor::class.java)
        assertThat(e.rhs).isInstanceOf(com.qkt.dsl.ast.NumLit::class.java)
        val rhs = e.rhs as com.qkt.dsl.ast.NumLit
        assertThat(rhs.value).isEqualByComparingTo(java.math.BigDecimal.valueOf(600_000L))
    }
}
