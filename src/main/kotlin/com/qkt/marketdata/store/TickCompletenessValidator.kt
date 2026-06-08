package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import java.time.LocalDate
import java.time.ZoneOffset

/** Completeness of one day's tick data. */
data class DayCompleteness(
    val day: LocalDate,
    val status: Status,
    val emptyHours: List<Int> = emptyList(),
) {
    enum class Status { NON_TRADING, COMPLETE, INCOMPLETE, MISSING }
}

/** Completeness report for a symbol over a date range. */
data class CompletenessReport(
    val symbol: String,
    val days: List<DayCompleteness>,
) {
    /** Days that should have data but don't (missing, or an interior session hour is empty). */
    val holes: List<DayCompleteness>
        get() =
            days.filter {
                it.status == DayCompleteness.Status.MISSING || it.status == DayCompleteness.Status.INCOMPLETE
            }

    val hasHoles: Boolean get() = holes.isNotEmpty()
}

/**
 * Validates that the locally stored ticks cover the trading sessions in a date range.
 *
 * For each day, the session hours are the UTC hours whose start the [TradingCalendar] reports in
 * session. A trading day must have a tick in every *interior* session hour (the first and last
 * session hour of the day are exempt, since the open/close boundary can be legitimately thin). A
 * trading day with no ticks is MISSING; a non-trading day (no session hours) expects nothing.
 */
object TickCompletenessValidator {
    fun validate(
        store: DataStore,
        symbol: String,
        from: LocalDate,
        to: LocalDate,
        calendar: TradingCalendar,
    ): CompletenessReport {
        val days = mutableListOf<DayCompleteness>()
        var day = from
        while (!day.isAfter(to)) {
            days += classify(store, symbol, day, calendar)
            day = day.plusDays(1)
        }
        return CompletenessReport(symbol, days)
    }

    private fun classify(
        store: DataStore,
        symbol: String,
        day: LocalDate,
        calendar: TradingCalendar,
    ): DayCompleteness {
        val sessionHours =
            (0..23).filter { h ->
                val start = day.atStartOfDay(ZoneOffset.UTC).plusHours(h.toLong()).toInstant()
                calendar.isInSession(symbol, start)
            }
        if (sessionHours.isEmpty()) return DayCompleteness(day, DayCompleteness.Status.NON_TRADING)

        val hoursWithTicks = hoursWithTicks(store, symbol, day)
        if (hoursWithTicks.isEmpty()) return DayCompleteness(day, DayCompleteness.Status.MISSING)

        // Exempt the boundary session hours; require coverage of the interior ones.
        val interior = sessionHours.drop(1).dropLast(1)
        val emptyInterior = interior.filter { it !in hoursWithTicks }
        return if (emptyInterior.isEmpty()) {
            DayCompleteness(day, DayCompleteness.Status.COMPLETE)
        } else {
            DayCompleteness(day, DayCompleteness.Status.INCOMPLETE, emptyInterior)
        }
    }

    /** The set of UTC hours (0..23) that contain at least one tick in the stored day file. */
    private fun hoursWithTicks(
        store: DataStore,
        symbol: String,
        day: LocalDate,
    ): Set<Int> {
        val path = store.dayFile(symbol, day) ?: return emptySet()
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hours = mutableSetOf<Int>()
        CsvTickFeed(path).use { feed ->
            while (true) {
                val t = feed.next() ?: break
                hours += ((t.timestamp - dayStart) / 3_600_000L).toInt()
            }
        }
        return hours
    }
}
