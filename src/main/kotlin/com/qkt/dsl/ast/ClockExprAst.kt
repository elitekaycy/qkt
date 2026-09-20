package com.qkt.dsl.ast

/** A `NOW.<field>` read of the evaluation clock, in UTC. */
data class NowAccessor(
    val field: NowField,
) : ExprAst

/** Fields exposed through the `NOW.*` namespace. */
enum class NowField {
    HOUR_UTC,
    MINUTE_UTC,
    WEEKDAY,
    MONTH,
    DAY,
    DAYS_IN_MONTH,
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
