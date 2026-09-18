package com.qkt.dsl.compile

import com.qkt.dsl.ast.TimeOfDay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Next-fire time math for [ScheduleRunner] triggers: a local time of day in a zone, a minute
 * past every UTC hour, and a local time of day on weekdays only. Day rolls use local-date
 * arithmetic so DST transitions never shift a fire by an hour.
 */
internal object ScheduleFireTimes {
    private const val HOUR_MS = 3_600_000L

    /**
     * Resolve next-fire for a time-of-day in a named zone. `today` is the local
     * calendar date in [zone]; the candidate is that date at the local clock
     * time the trigger declared, converted to UTC epoch ms. Handles DST
     * correctly because the zone resolution does — e.g. NY 09:00 is a different
     * UTC instant in March vs. November.
     */
    fun nextAt(
        t: TimeOfDay,
        zone: ZoneId,
        from: Instant,
        fromMs: Long,
    ): Long {
        val time = LocalTime.of(t.hour, t.minute, t.second)
        val todayLocal = from.atZone(zone).toLocalDate()
        val candidate = localInstantMs(todayLocal, time, zone)
        // Roll to the NEXT LOCAL DAY by date arithmetic, never by adding 24h of epoch
        // millis: across a DST transition the same local clock time is a different
        // UTC offset, and a fixed-day add fires an hour early or late.
        return if (candidate >= fromMs) candidate else localInstantMs(todayLocal.plusDays(1), time, zone)
    }

    private fun localInstantMs(
        date: java.time.LocalDate,
        time: LocalTime,
        zone: ZoneId,
    ): Long =
        date
            .atTime(time)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    fun nextEveryHour(
        minuteOffset: Int,
        from: Instant,
        fromMs: Long,
    ): Long {
        val thisHour =
            from
                .atZone(ZoneOffset.UTC)
                .withMinute(minuteOffset)
                .withSecond(0)
                .withNano(0)
                .toInstant()
                .toEpochMilli()
        return if (thisHour >= fromMs) thisHour else thisHour + HOUR_MS
    }

    /**
     * Mon-Fri only, evaluated in the trigger's local [zone]. A weekend day in
     * London might still be a Friday in Tokyo at the same UTC instant — so the
     * weekday check uses the local calendar date, not UTC.
     */
    fun nextWeekday(
        t: TimeOfDay,
        zone: ZoneId,
        from: Instant,
        fromMs: Long,
    ): Long {
        val time = LocalTime.of(t.hour, t.minute, t.second)
        var date = from.atZone(zone).toLocalDate()
        var candidate = localInstantMs(date, time, zone)
        if (candidate < fromMs) {
            date = date.plusDays(1)
            candidate = localInstantMs(date, time, zone)
        }
        // Local-date arithmetic for the same DST reason as [nextAt].
        while (!isWeekday(candidate, zone)) {
            date = date.plusDays(1)
            candidate = localInstantMs(date, time, zone)
        }
        return candidate
    }

    private fun isWeekday(
        epochMs: Long,
        zone: ZoneId,
    ): Boolean {
        val dow =
            Instant
                .ofEpochMilli(epochMs)
                .atZone(zone)
                .dayOfWeek
                .value
        return dow in 1..5 // Mon-Fri
    }
}
