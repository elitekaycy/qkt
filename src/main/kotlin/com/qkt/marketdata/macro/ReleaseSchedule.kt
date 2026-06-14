package com.qkt.marketdata.macro

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Maps a macro observation date to the instant its value first becomes public knowledge.
 *
 * The correctness core of the macro path: a value dated D is NOT known during day D — the
 * authority (FRED) publishes it the next business morning. A backtest at intraday time T must
 * only see values released at or before T, or it has look-ahead. This models a fixed lag:
 * advance [lagBusinessDays] business days (skipping weekends) from the observation date and
 * release at [releaseUtcHour]:00 UTC.
 *
 * e.g. a value observed Fri 2024-03-01 releases Mon 2024-03-04 13:00 UTC (not Saturday).
 */
object ReleaseSchedule {
    fun releaseTimeMs(
        observationDate: LocalDate,
        lagBusinessDays: Int = 1,
        releaseUtcHour: Int = 13,
    ): Long {
        var d = observationDate
        var remaining = lagBusinessDays
        while (remaining > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) remaining--
        }
        return d.atTime(releaseUtcHour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
