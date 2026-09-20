package com.qkt.dsl.compile

import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.SessionWindow
import java.math.BigDecimal

/**
 * Compiles the clock-reading expressions: `NOW.<field>`, calendar and session windows, and
 * `LAST_TRADING_DAY_OF_MONTH`. Every one reads the evaluation clock (`ctx.nowMs()`) in UTC;
 * none holds state between evaluations.
 */
internal object ClockExprCompiler {
    fun compileNow(acc: NowAccessor): CompiledExpr =
        CompiledExpr { ctx ->
            val nowMs = ctx.nowMs()
            when (acc.field) {
                NowField.EPOCH_MS -> Value.Num(BigDecimal.valueOf(nowMs))
                else -> {
                    val z =
                        java.time.Instant
                            .ofEpochMilli(nowMs)
                            .atZone(java.time.ZoneOffset.UTC)
                    val n =
                        when (acc.field) {
                            NowField.HOUR_UTC -> z.hour
                            NowField.MINUTE_UTC -> z.minute
                            NowField.WEEKDAY -> z.dayOfWeek.value - 1
                            NowField.MONTH -> z.monthValue
                            NowField.DAY -> z.dayOfMonth
                            NowField.DAYS_IN_MONTH -> z.toLocalDate().lengthOfMonth()
                            NowField.DATE_UTC -> z.toLocalDate().toEpochDay().toInt()
                            NowField.EPOCH_MS -> error("handled above")
                        }
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            }
        }

    fun compileCalendarWindow(win: CalendarWindow): CompiledExpr {
        // Encode each (month, day) as MMDD so a plain integer compare orders dates within a
        // year, e.g. Aug 15 -> 815, Oct 31 -> 1031.
        val start = win.startMonth * 100 + win.startDay
        val end = win.endMonth * 100 + win.endDay
        return CompiledExpr { ctx ->
            val z =
                java.time.Instant
                    .ofEpochMilli(ctx.nowMs())
                    .atZone(java.time.ZoneOffset.UTC)
            val cur = z.monthValue * 100 + z.dayOfMonth
            // A non-wrapping window is a simple range; a wrapping one (start later than end,
            // e.g. Dec 1 -> Jan 31) matches dates at or after the start OR at or before the end.
            val hit = if (start <= end) cur in start..end else cur >= start || cur <= end
            Value.of(hit)
        }
    }

    fun compileSessionWindow(win: SessionWindow): CompiledExpr {
        // Encode each (hour, minute) as minutes-since-midnight so a plain integer compare orders
        // times of day, e.g. 00:30 -> 30, 01:30 -> 90.
        val start = win.startHour * 60 + win.startMinute
        val end = win.endHour * 60 + win.endMinute
        return CompiledExpr { ctx ->
            val z =
                java.time.Instant
                    .ofEpochMilli(ctx.nowMs())
                    .atZone(java.time.ZoneOffset.UTC)
            val cur = z.hour * 60 + z.minute
            // A non-wrapping window is a simple range; a wrapping one (start later than end, e.g.
            // 23:00 -> 01:00) matches times at or after the start OR at or before the end.
            val hit = if (start <= end) cur in start..end else cur >= start || cur <= end
            Value.of(hit)
        }
    }

    fun compileLastTradingDayOfMonth(): CompiledExpr =
        CompiledExpr { ctx ->
            val date =
                java.time.Instant
                    .ofEpochMilli(ctx.nowMs())
                    .atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate()
            // The last weekday of the month: roll the calendar last day back off any weekend.
            val lastDay = date.withDayOfMonth(date.lengthOfMonth())
            val lastTradingDay =
                when (lastDay.dayOfWeek) {
                    java.time.DayOfWeek.SATURDAY -> lastDay.minusDays(1)
                    java.time.DayOfWeek.SUNDAY -> lastDay.minusDays(2)
                    else -> lastDay
                }
            Value.of(date == lastTradingDay)
        }
}
