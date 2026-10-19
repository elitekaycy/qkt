package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.common.TradingCalendar
import java.time.LocalDate

/**
 * Bar coverage of a date range: trading days asked for, days covered, and the days with a hole ([missingDays],
 * missing or incomplete), with every day's verdict in [days].
 */
data class BarCoverage(
    val requestedTradingDays: Int,
    val coveredTradingDays: Int,
    val missingDays: List<LocalDate>,
    val days: List<DayCompleteness> = emptyList(),
)

/**
 * Coverage validation for the prebuilt-bars research tier. A day's bars come from a built `<day>.bin` or, when
 * [fetched] is given, a fetched or imported `<day>.csv` at the same timeframe (`--bars` reads either), the file
 * that holds bars winning. A file with no bars is no coverage: a header-only `.bin` built before its ticks
 * arrived reads as a missing day. Days are judged by [SessionCoverage], each bar covering its timeframe, so the
 * backtest's bar check, its tick check and `qkt data verify` share one rule. Timeframes longer than a day keep
 * one bar per several day files, so there a day is covered when its file holds a bar or the calendar has no
 * session on it.
 */
object BarCompletenessValidator {
    private const val DAY_MS = 86_400_000L
    private const val LATER_LOOKAHEAD_DAYS = 7

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
        val starts = { day: LocalDate -> startTimes(store, fetched, broker, symbol, timeframe, day) }
        val coverage = SessionCoverage(calendar, symbol, "bar")
        val days =
            if (timeframe.durationMs > DAY_MS) {
                coverage.classify(from, to, { emptyList() }).map { d ->
                    val has = starts(d.day)?.isNotEmpty() == true
                    if (d.status == DayCompleteness.Status.NON_TRADING || has) {
                        d.copy(status = if (has) DayCompleteness.Status.COMPLETE else d.status)
                    } else {
                        DayCompleteness(d.day, DayCompleteness.Status.MISSING, detail = "no bars")
                    }
                }
            } else {
                val activity = { day: LocalDate ->
                    starts(day)?.let { ActivityRunBuilder.of(it, timeframe.durationMs) }
                }
                coverage.classify(from, to, activity) { after ->
                    generateSequence(after.plusDays(1)) { it.plusDays(1) }
                        .take(LATER_LOOKAHEAD_DAYS)
                        .any { starts(it)?.isNotEmpty() == true }
                }
            }
        val expected = days.filter { it.status != DayCompleteness.Status.NON_TRADING }
        val holes = expected.filter { it.hole }.map { it.day }
        return BarCoverage(expected.size, expected.size - holes.size, holes, days)
    }

    /** The day's bar start times from whichever stored file holds bars; null when neither file exists. */
    private fun startTimes(
        store: BinaryBarStore,
        fetched: LocalBarStore?,
        broker: String,
        symbol: String,
        timeframe: TimeWindow,
        day: LocalDate,
    ): LongArray? {
        val built = store.startTimes(broker, symbol, timeframe, day)
        if (built != null && built.isNotEmpty()) return built
        val csv =
            fetched
                ?.takeIf { it.hasDay(broker, symbol, timeframe.canonicalSpec(), day) }
                ?.let { bars ->
                    runCatching {
                        bars.readDay(
                            broker,
                            symbol,
                            timeframe.canonicalSpec(),
                            day,
                        )
                    }.getOrDefault(emptyList())
                }?.map { it.startTime }
                ?.toLongArray()
        return if (csv != null && csv.isNotEmpty()) csv else built ?: csv
    }
}
