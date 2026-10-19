package com.qkt.cli.data

import com.qkt.cli.BacktestContext
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.store.ActivityRun
import com.qkt.marketdata.store.DayCompleteness
import com.qkt.marketdata.store.SessionCoverage
import java.time.DayOfWeek
import java.time.LocalDate

/** One `qkt data verify` verdict: what to print and whether it counts as flagged. */
internal data class VerifyVerdict(
    val text: String,
    val flagged: Boolean,
)

/**
 * The coverage half of `qkt data verify`, judged by the rule a backtest applies ([SessionCoverage]), so verify
 * and a backtest agree on every day: holidays print `holiday`, holiday hours `short session (last tick 19:42Z)`,
 * a stretch of session time with no data over 90 minutes `GAP (no ticks 12:05Z-15:15Z)`, a trading day with no
 * file `MISSING`. The first or last stored day cut short is shown as `partial`, not flagged: verify cannot tell the
 * end of an import from a fetch that stopped, and a backtest asking for that day refuses it.
 */
internal class VerifyCoverage(
    bareSymbol: String,
    /** True when the store holds data on a Saturday: a 24/7 market, whatever its name. */
    tradesSaturdays: Boolean,
    private val unit: String,
    stored: Map<LocalDate, List<ActivityRun>>,
) {
    private val calendar: TradingCalendar =
        if (tradesSaturdays) TradingCalendar.crypto() else BacktestContext.defaultCalendars().calendarFor(bareSymbol)

    private val days: Map<LocalDate, DayCompleteness> =
        if (stored.isEmpty()) {
            emptyMap()
        } else {
            SessionCoverage(calendar, bareSymbol, unit)
                .classify(stored.keys.min(), stored.keys.max(), { stored[it] })
                .associateBy { it.day }
        }

    private val storedDays = stored.keys

    /** The verdict for stored [day], whose file holds no data when [empty]. */
    fun of(
        day: LocalDate,
        empty: Boolean,
    ): VerifyVerdict {
        val d = days[day] ?: return VerifyVerdict("ok", false)
        return when (d.status) {
            DayCompleteness.Status.NON_TRADING ->
                when {
                    d.holiday -> VerifyVerdict(DayCompleteness.HOLIDAY, false)
                    empty -> VerifyVerdict("closed (0 ${unit}s)", false)
                    else -> VerifyVerdict("ok", false)
                }
            DayCompleteness.Status.COMPLETE -> VerifyVerdict(if (d.detail == "ok") "ok" else "ok (${d.detail})", false)
            DayCompleteness.Status.SHORT -> VerifyVerdict(d.detail, false)
            DayCompleteness.Status.INCOMPLETE ->
                if (d.atEdge) {
                    // Where the stored data starts or ends: an import's last day cut off by its file, or a fetch
                    // that stopped. Shown, not flagged; a backtest asking for the day still refuses it.
                    VerifyVerdict("partial, the end of the stored data (${d.detail})", false)
                } else {
                    VerifyVerdict("GAP (${d.detail})", true)
                }
            DayCompleteness.Status.MISSING ->
                VerifyVerdict(if (empty) "EMPTY (0 ${unit}s)" else "MISSING (${d.detail})", true)
        }
    }

    /**
     * The days inside the stored span with no file worth a line: trading days (`MISSING`, flagged, a run with
     * no stored day inside it on one line) and weekdays a holiday or short session leaves without one (shown,
     * not flagged), e.g. `2023-12-25  short session (first bar 12-26 01:00Z) (no file)`.
     */
    fun absentLines(): List<VerifyVerdict> {
        val out = mutableListOf<VerifyVerdict>()
        val run = mutableListOf<LocalDate>()

        fun flush() {
            if (run.isEmpty()) return
            val span = if (run.size == 1) "${run.first()}" else "${run.first()} … ${run.last()}"
            val what = if (run.size == 1) "a trading day" else "${run.size} trading days"
            out += VerifyVerdict("$span  MISSING (no file: $what)", true)
            run.clear()
        }
        for ((day, d) in days.toSortedMap()) {
            if (day in storedDays) {
                flush()
                continue
            }
            val weekday = day.dayOfWeek !in WEEKEND
            when {
                d.hole -> run += day
                !weekday || d.status == DayCompleteness.Status.NON_TRADING && !d.holiday -> Unit
                d.detail == "ok" -> Unit
                else -> out += VerifyVerdict("$day  ${d.detail} (no file)", false)
            }
        }
        flush()
        return out
    }

    private companion object {
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
