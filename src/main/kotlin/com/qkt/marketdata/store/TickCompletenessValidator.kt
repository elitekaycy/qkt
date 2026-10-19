package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import java.time.LocalDate

/**
 * Validates that the locally stored ticks cover the trading sessions in a date range, by the rule in
 * [SessionCoverage]: sessions from the [TradingCalendar] (holidays need nothing), holes as stretches of
 * session time without a tick longer than 90 minutes, late opens and early closes accepted as short sessions
 * when the store holds ticks after them. A file that cannot be read counts as holding the ticks read before
 * the failure, so a corrupt day is a hole rather than a crash.
 */
object TickCompletenessValidator {
    /** How far past the range a later stored tick is looked for (a long weekend plus a holiday). */
    private const val LATER_LOOKAHEAD_DAYS = 7

    fun validate(
        store: DataStore,
        symbol: String,
        from: LocalDate,
        to: LocalDate,
        calendar: TradingCalendar,
    ): CompletenessReport {
        val activity = { day: LocalDate -> store.dayFile(symbol, day)?.let { DayFileIntegrity.inspect(it).runs } }
        val days =
            SessionCoverage(calendar, symbol, "tick").classify(from, to, activity) { after ->
                generateSequence(after.plusDays(1)) { it.plusDays(1) }
                    .take(LATER_LOOKAHEAD_DAYS)
                    .any { activity(it).orEmpty().isNotEmpty() }
            }
        return CompletenessReport(symbol, days)
    }
}
