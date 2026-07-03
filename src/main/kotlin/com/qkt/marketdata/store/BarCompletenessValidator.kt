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

/** File-level coverage validation for the prebuilt-bars research tier. */
object BarCompletenessValidator {
    fun validate(
        store: BinaryBarStore,
        broker: String,
        symbol: String,
        timeframe: TimeWindow,
        from: LocalDate,
        to: LocalDate,
        calendar: TradingCalendar,
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
        val missing = expected.filterNot { store.hasDay(broker, symbol, timeframe, it) }
        return BarCoverage(expected.size, expected.size - missing.size, missing)
    }
}
