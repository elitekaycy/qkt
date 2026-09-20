package com.qkt.dsl.parse

import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.LastTradingDayOfMonth
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SessionWindow

/**
 * Parses the expressions that read the bar clock: `NOW.<field>` and the calendar predicates
 * `CALENDAR_WINDOW`, `SESSION_WINDOW` and `LAST_TRADING_DAY_OF_MONTH`. Bad arguments are recorded
 * as parse errors rather than thrown, so the rest of the strategy still parses and every mistake
 * is reported in one pass.
 */
internal class ClockExprParser(
    private val cursor: TokenCursor,
) {
    /**
     * Build a [CalendarWindow] from a `CALENDAR_WINDOW(startMonth, startDay, endMonth, endDay)`
     * call. All four arguments must be integer literalParser; month must be 1-12 and day 1-31.
     * Violations are recorded as parse errors so the strategy fails to compile rather than
     * silently misbehaving. [at] is the call token, used for error position.
     */
    fun buildCalendarWindow(
        args: List<ExprAst>,
        at: Token,
    ): ExprAst {
        val ints =
            args.map { a ->
                (a as? NumLit)?.value?.let { if (it.stripTrailingZeros().scale() <= 0) it.toInt() else null }
            }
        if (args.size != 4 || ints.any { it == null }) {
            cursor.errors +=
                ParseError(
                    at.line,
                    at.col,
                    "CALENDAR_WINDOW expects 4 integer literalParser: startMonth, startDay, endMonth, endDay",
                )
            return CalendarWindow(1, 1, 1, 1)
        }
        val (sm, sd, em, ed) = ints.filterNotNull()
        if (sm !in 1..12 || em !in 1..12 || sd !in 1..31 || ed !in 1..31) {
            cursor.errors += ParseError(at.line, at.col, "CALENDAR_WINDOW month must be 1-12 and day 1-31")
        }
        return CalendarWindow(sm, sd, em, ed)
    }

    /**
     * Build a [SessionWindow] from a `SESSION_WINDOW(startHour, startMinute, endHour, endMinute)`
     * call. All four arguments must be integer literalParser; hour must be 0-23 and minute 0-59.
     * Violations are recorded as parse errors so the strategy fails to compile rather than
     * silently misbehaving. [at] is the call token, used for error position.
     */
    fun buildSessionWindow(
        args: List<ExprAst>,
        at: Token,
    ): ExprAst {
        val ints =
            args.map { a ->
                (a as? NumLit)?.value?.let { if (it.stripTrailingZeros().scale() <= 0) it.toInt() else null }
            }
        if (args.size != 4 || ints.any { it == null }) {
            cursor.errors +=
                ParseError(
                    at.line,
                    at.col,
                    "SESSION_WINDOW expects 4 integer literalParser: startHour, startMinute, endHour, endMinute",
                )
            return SessionWindow(0, 0, 0, 0)
        }
        val (sh, sm, eh, em) = ints.filterNotNull()
        if (sh !in 0..23 || eh !in 0..23 || sm !in 0..59 || em !in 0..59) {
            cursor.errors += ParseError(at.line, at.col, "SESSION_WINDOW hour must be 0-23 and minute 0-59")
        }
        return SessionWindow(sh, sm, eh, em)
    }

    /**
     * Build a [LastTradingDayOfMonth] from a `LAST_TRADING_DAY_OF_MONTH()` call. The predicate
     * takes no arguments; any argument is a parse error. [at] is the call token, for error position.
     */
    fun buildLastTradingDayOfMonth(
        args: List<ExprAst>,
        at: Token,
    ): ExprAst {
        if (args.isNotEmpty()) {
            cursor.errors += ParseError(at.line, at.col, "LAST_TRADING_DAY_OF_MONTH takes no arguments")
        }
        return LastTradingDayOfMonth
    }

    /** Parses `NOW` or `NOW.<field>`; the current token is `NOW`. */
    fun parseNowAccessor(): ExprAst {
        cursor.advance()
        return if (cursor.peek().kind == TokenKind.DOT) {
            cursor.advance()
            // NOW.<field>. `WEEKDAY` is also a SCHEDULE token (#77), so we
            // accept either an IDENT or that specific keyword here and read
            // the lexeme — keeps `NOW.weekday` working as a field access.
            val fieldTok =
                when (cursor.peek().kind) {
                    // WEEKDAY and DAY are also SCHEDULE keywords; accept them here and read
                    // the lexeme so `NOW.weekday` / `NOW.day` work as field accesses.
                    TokenKind.IDENT, TokenKind.WEEKDAY, TokenKind.DAY -> cursor.advance()
                    else -> cursor.expect(TokenKind.IDENT, "expected NOW field name")
                }
            val field =
                when (fieldTok.lexeme.uppercase()) {
                    "HOUR_UTC" -> NowField.HOUR_UTC
                    "MINUTE_UTC" -> NowField.MINUTE_UTC
                    "WEEKDAY" -> NowField.WEEKDAY
                    "MONTH" -> NowField.MONTH
                    "DAY" -> NowField.DAY
                    "DAYS_IN_MONTH" -> NowField.DAYS_IN_MONTH
                    "DATE_UTC" -> NowField.DATE_UTC
                    "EPOCH_MS" -> NowField.EPOCH_MS
                    else -> {
                        cursor.errors +=
                            ParseError(fieldTok.line, fieldTok.col, "unknown NOW field: ${fieldTok.lexeme}")
                        NowField.EPOCH_MS
                    }
                }
            NowAccessor(field)
        } else {
            NowAccessor(NowField.EPOCH_MS)
        }
    }
}
