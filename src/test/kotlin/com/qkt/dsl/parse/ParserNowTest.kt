package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.SessionWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserNowTest {
    private fun parseExprInLet(s: String): ExprAst {
        val r =
            Parser(Lexer("STRATEGY x VERSION 1\nLET v = $s").tokenize())
                .parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    private fun parseResult(s: String): ParseResult<*> =
        Parser(Lexer("STRATEGY x VERSION 1\nLET v = $s").tokenize()).parseStrategy()

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
        assertThat((parseExprInLet("NOW.month") as NowAccessor).field).isEqualTo(NowField.MONTH)
        assertThat((parseExprInLet("NOW.day") as NowAccessor).field).isEqualTo(NowField.DAY)
        assertThat((parseExprInLet("NOW.date_utc") as NowAccessor).field).isEqualTo(NowField.DATE_UTC)
        assertThat((parseExprInLet("NOW.epoch_ms") as NowAccessor).field).isEqualTo(NowField.EPOCH_MS)
    }

    @Test
    fun `CALENDAR_WINDOW parses to a CalendarWindow node`() {
        val e = parseExprInLet("calendar_window(8, 15, 10, 31)") as CalendarWindow
        assertThat(e.startMonth).isEqualTo(8)
        assertThat(e.startDay).isEqualTo(15)
        assertThat(e.endMonth).isEqualTo(10)
        assertThat(e.endDay).isEqualTo(31)
    }

    @Test
    fun `CALENDAR_WINDOW is case-insensitive`() {
        val e = parseExprInLet("CALENDAR_WINDOW(12, 1, 1, 31)") as CalendarWindow
        assertThat(e.startMonth).isEqualTo(12)
        assertThat(e.endMonth).isEqualTo(1)
    }

    @Test
    fun `CALENDAR_WINDOW with wrong arg count fails to parse`() {
        assertThat(parseResult("calendar_window(8, 15, 10)")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `CALENDAR_WINDOW with out-of-range month fails to parse`() {
        assertThat(parseResult("calendar_window(13, 1, 1, 31)")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `CALENDAR_WINDOW with out-of-range day fails to parse`() {
        assertThat(parseResult("calendar_window(1, 32, 2, 1)")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `CALENDAR_WINDOW with a non-literal arg fails to parse`() {
        assertThat(parseResult("calendar_window(8, 15, 10, ema(btc.close, 3))"))
            .isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `SESSION_WINDOW parses to a SessionWindow node`() {
        val e = parseExprInLet("session_window(0, 30, 1, 30)") as SessionWindow
        assertThat(e.startHour).isEqualTo(0)
        assertThat(e.startMinute).isEqualTo(30)
        assertThat(e.endHour).isEqualTo(1)
        assertThat(e.endMinute).isEqualTo(30)
    }

    @Test
    fun `SESSION_WINDOW is case-insensitive and wraps midnight`() {
        val e = parseExprInLet("SESSION_WINDOW(23, 0, 1, 0)") as SessionWindow
        assertThat(e.startHour).isEqualTo(23)
        assertThat(e.endHour).isEqualTo(1)
    }

    @Test
    fun `SESSION_WINDOW with wrong arg count fails to parse`() {
        assertThat(parseResult("session_window(0, 30, 1)")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `SESSION_WINDOW with out-of-range hour fails to parse`() {
        assertThat(parseResult("session_window(24, 0, 1, 0)")).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `SESSION_WINDOW with out-of-range minute fails to parse`() {
        assertThat(parseResult("session_window(0, 60, 1, 0)")).isInstanceOf(ParseResult.Failure::class.java)
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
