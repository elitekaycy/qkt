package com.qkt.marketdata.macro

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Maps a macro observation date to the instant its value first becomes public knowledge.
 *
 * The correctness core of the macro path: a value dated D is NOT known during day D. The Fed posts
 * its H.15 rates (the source of FRED's daily rate series) at 4:15 p.m. New York time on each federal
 * business day with the previous business day's values, so a value dated D releases at
 * [RELEASE_TIME] New York on the [lagBusinessDays]th federal business day after D
 * ([UsFederalCalendar]). A backtest at intraday time T must only see values released at or before
 * T, or it has look-ahead.
 *
 * e.g. a value observed Fri 2024-05-24 releases Tue 2024-05-28 16:30 New York (Memorial Day Monday
 * is closed). An earlier rule released at 13:00 UTC on the next weekday: about seven hours before
 * the posting, and a day early after every holiday.
 */
object ReleaseSchedule {
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

    /** A quarter hour after the 4:15 p.m. posting, matching qkt-data-hub's FRED backfill. */
    val RELEASE_TIME: LocalTime = LocalTime.of(16, 30)

    fun releaseTimeMs(
        observationDate: LocalDate,
        lagBusinessDays: Int = 1,
    ): Long =
        UsFederalCalendar
            .nextBusinessDay(observationDate, lagBusinessDays)
            .atTime(RELEASE_TIME)
            .atZone(NEW_YORK)
            .toInstant()
            .toEpochMilli()
}
