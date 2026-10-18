package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.common.TradingCalendar
import java.time.LocalDate
import java.time.ZoneOffset

data class BarCoverage(
    val requestedTradingDays: Int,
    val coveredTradingDays: Int,
    val missingDays: List<LocalDate>,
)

/**
 * File-level coverage validation for the prebuilt-bars research tier. A day is covered by a built `<day>.bin` or,
 * when [fetched] is given, by a fetched or imported `<day>.csv` at the same timeframe: `--bars` reads either. A file
 * with no bars is no coverage: a header-only `.bin` built before its ticks arrived reads as a missing day.
 */
object BarCompletenessValidator {
    fun validate(
        store: BinaryBarStore,
        broker: String,
        symbol: String,
        timeframe: TimeWindow,
        from: LocalDate,
        to: LocalDate,
        calendar: TradingCalendar,
        fetched: LocalBarStore? = null,
    ): BarCoverage {
        val expected = mutableListOf<LocalDate>()
        var day = from
        while (!day.isAfter(to)) {
            val hasSession =
                (0..23).any { hour ->
                    calendar.isInSession(
                        symbol,
                        day.atStartOfDay(ZoneOffset.UTC).plusHours(hour.toLong()).toInstant(),
                    )
                }
            if (hasSession) expected += day
            day = day.plusDays(1)
        }
        val missing =
            expected.filterNot {
                store.hasDay(broker, symbol, timeframe, it) ||
                    fetched?.readDay(broker, symbol, timeframe.canonicalSpec(), it)?.isNotEmpty() == true
            }
        return BarCoverage(expected.size, expected.size - missing.size, missing)
    }
}
