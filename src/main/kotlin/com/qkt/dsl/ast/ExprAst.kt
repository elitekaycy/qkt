package com.qkt.dsl.ast

import java.math.BigDecimal

sealed interface ExprAst

data class NumLit(
    val value: BigDecimal,
) : ExprAst

data class BoolLit(
    val value: Boolean,
) : ExprAst

data class StringLit(
    val value: String,
) : ExprAst

data class Ref(
    val name: String,
    val snapshot: SnapshotKind? = null,
) : ExprAst

data class StreamFieldRef(
    val stream: String,
    val field: String,
) : ExprAst

data class NowAccessor(
    val field: NowField,
) : ExprAst

enum class NowField {
    HOUR_UTC,
    MINUTE_UTC,
    WEEKDAY,
    MONTH,
    DAY,
    DATE_UTC,
    EPOCH_MS,
}

/**
 * True while the current UTC date falls inside an annual calendar window, inclusive of both
 * ends. The window is expressed as a start and end (month, day-of-month) and repeats every
 * year, so it lets a strategy gate entries/exits to a recurring seasonal range without
 * hard-coding a year.
 *
 * A window may wrap the year boundary: when the start is later in the calendar than the end,
 * the window runs from the start through year-end and on into the next year up to the end.
 * e.g. `CALENDAR_WINDOW(8, 15, 10, 31)` is Aug 15 - Oct 31 (Diwali season);
 * `CALENDAR_WINDOW(12, 1, 1, 31)` wraps Dec 1 - Jan 31 (Chinese New Year restocking).
 */
data class CalendarWindow(
    val startMonth: Int,
    val startDay: Int,
    val endMonth: Int,
    val endDay: Int,
) : ExprAst

/**
 * True while the current UTC time-of-day falls inside a daily window, inclusive of both ends.
 * The window is a start and end (hour, minute) and repeats every day, so it gates entries/exits
 * to a recurring intraday session without referencing a date.
 *
 * A window may wrap midnight: when the start is later in the day than the end, it runs from the
 * start to end-of-day and on into the next day up to the end.
 * e.g. `SESSION_WINDOW(0, 30, 1, 30)` is 00:30-01:30 UTC (Asian open);
 * `SESSION_WINDOW(23, 0, 1, 0)` wraps 23:00-01:00 UTC.
 */
data class SessionWindow(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
) : ExprAst

/**
 * True on the last trading day of the current UTC month — the last weekday (Monday-Friday)
 * of the month. A clock-reading boolean primitive like [CalendarWindow]; takes no arguments.
 *
 * It isolates month-end flow (e.g. the fiduciary fix-rebalancing that concentrates on the
 * final session of the month) without hard-coding dates, which shift 28-31 and slide off
 * weekends. e.g. if a month ends on Saturday the 31st, the last trading day is Friday the 30th.
 *
 * "Trading day" here means a weekday; it does not consult an exchange holiday calendar, so a
 * public holiday landing on the last weekday is still treated as the last trading day. This is
 * the faithful approximation for 24/5 FX, which trades every weekday.
 */
data object LastTradingDayOfMonth : ExprAst

data class IndicatorCall(
    val name: String,
    val args: List<ExprAst>,
) : ExprAst

data class BinaryOp(
    val op: BinOp,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class UnaryOp(
    val op: UnOp,
    val arg: ExprAst,
) : ExprAst

data class CmpOp(
    val op: Cmp,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class Between(
    val v: ExprAst,
    val lo: ExprAst,
    val hi: ExprAst,
) : ExprAst

data class InList(
    val v: ExprAst,
    val members: List<ExprAst>,
) : ExprAst

data class Crosses(
    val direction: CrossDir,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class CaseWhen(
    val branches: List<Pair<ExprAst, ExprAst>>,
    val elseExpr: ExprAst,
) : ExprAst

data class Aggregate(
    val fn: AggFn,
    val series: ExprAst,
    val window: Window,
) : ExprAst

data class AccountRef(
    val field: String,
) : ExprAst

data class PositionRef(
    val stream: String,
) : ExprAst

data class StateAccessor(
    val source: StateSource,
    val key: String,
) : ExprAst

data class FuncCall(
    val name: String,
    val args: List<ExprAst>,
) : ExprAst

data object StackEntryRef : ExprAst

/**
 * Phase 37: references the parent leg's filled quantity inside a `STACK_AT SIZING`
 * expression. Rejected by [com.qkt.dsl.compile.ExprCompiler] outside that context.
 */
data object EntryQty : ExprAst

/**
 * Phase 24: explicit null test against [com.qkt.dsl.compile.Value.Undefined].
 *
 * `<expr> IS NULL` evaluates to `Value.Bool(true)` iff the inner expression yields
 * `Value.Undefined`. `IS NOT NULL` is the inverse. The result is always a defined
 * `Value.Bool` — `IsNull` never propagates undefined itself.
 */
data class IsNull(
    val expr: ExprAst,
    val negated: Boolean,
) : ExprAst
