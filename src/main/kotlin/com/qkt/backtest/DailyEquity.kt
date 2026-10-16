package com.qkt.backtest

import com.qkt.common.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Account equity over one UTC trading day: first, highest, lowest and last sample (#1277). */
data class DailyEquity(
    val date: LocalDate,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
)

/** Equity return of one calendar month: month-end close over the previous month-end close, minus one. */
data class MonthlyReturn(
    val month: YearMonth,
    val value: BigDecimal,
)

/**
 * Folds full-resolution equity samples into one [DailyEquity] row per UTC day, so a consumer never
 * has to rebuild a calendar from the thinned chart curve. Days are keyed the same way as
 * `pnl_components.csv` (UTC midnight). Memory is one row per day seen, about 1,500 for six years.
 */
class DailyEquityAccumulator {
    private val days = ArrayList<DailyEquity>()
    private var currentDay: Long = Long.MIN_VALUE
    private var open: BigDecimal = Money.ZERO
    private var high: BigDecimal = Money.ZERO
    private var low: BigDecimal = Money.ZERO
    private var close: BigDecimal = Money.ZERO

    /** Whether the day in progress holds only one sample, taken at exactly its midnight. */
    private var onlyMidnight = false

    fun accept(
        timestamp: Long,
        equity: BigDecimal,
    ) {
        val day = Math.floorDiv(timestamp, MS_PER_DAY)
        if (day != currentDay) {
            flush()
            currentDay = day
            open = equity
            high = equity
            low = equity
            onlyMidnight = Math.floorMod(timestamp, MS_PER_DAY) == 0L
        } else {
            onlyMidnight = false
        }
        if (equity > high) high = equity
        if (equity < low) low = equity
        close = equity
    }

    /**
     * Every completed day plus the day in progress, in order. Non-destructive. A last day holding a single sample
     * at exactly its midnight is the previous day's close, not a day of its own: equity is sampled before the
     * boundary tick, so a run ending at midnight (`--to 2026-10-15`) closes 2026-10-14 there and never reports a
     * 2026-10-15 row, which would count a day the run did not cover.
     */
    fun result(): List<DailyEquity> {
        if (currentDay == Long.MIN_VALUE) return emptyList()
        val previous = days.lastOrNull()
        if (!onlyMidnight || previous == null || previous.date.toEpochDay() != currentDay - 1) return days + current()
        val closing = current()
        return days.dropLast(1) +
            previous.copy(
                high = previous.high.max(closing.close),
                low = previous.low.min(closing.close),
                close = closing.close,
            )
    }

    private fun flush() {
        if (currentDay != Long.MIN_VALUE) days.add(current())
    }

    private fun current(): DailyEquity =
        DailyEquity(
            date = LocalDate.ofEpochDay(currentDay),
            open = open.setScale(Money.SCALE, Money.ROUNDING),
            high = high.setScale(Money.SCALE, Money.ROUNDING),
            low = low.setScale(Money.SCALE, Money.ROUNDING),
            close = close.setScale(Money.SCALE, Money.ROUNDING),
        )

    companion object {
        private const val MS_PER_DAY = 86_400_000L
    }
}

/**
 * Month-over-month equity returns from a daily series. The first month is measured from the first
 * day's open, every later month from the previous month's last close, so compounding every return
 * gives exactly `finalClose / firstOpen - 1` (the run's total return). Empty when [daily] is empty.
 */
fun monthlyReturns(daily: List<DailyEquity>): List<MonthlyReturn> {
    if (daily.isEmpty()) return emptyList()
    val out = ArrayList<MonthlyReturn>()
    var base = daily.first().open
    var month = YearMonth.from(daily.first().date.atStartOfDay(ZoneOffset.UTC))
    var lastClose = daily.first().close
    for (row in daily) {
        val rowMonth = YearMonth.from(row.date)
        if (rowMonth != month) {
            out.add(MonthlyReturn(month, returnOf(base, lastClose)))
            base = lastClose
            month = rowMonth
        }
        lastClose = row.close
    }
    out.add(MonthlyReturn(month, returnOf(base, lastClose)))
    return out
}

private fun returnOf(
    base: BigDecimal,
    close: BigDecimal,
): BigDecimal =
    if (base.signum() == 0) {
        Money.ZERO
    } else {
        close.subtract(base).divide(base, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }
