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
 * `pnl_components.csv` (UTC midnight). Memory is one row per day seen, about 1,500 for six years, or none with
 * [keepRows] off (the worst daily drawdown alone, [DailyDrawdownAccumulator]).
 */
class DailyEquityAccumulator(
    private val keepRows: Boolean = true,
) {
    private val days = ArrayList<DailyEquity>()
    private var currentDay: Long = Long.MIN_VALUE
    private var open: BigDecimal = Money.ZERO
    private var high: BigDecimal = Money.ZERO
    private var low: BigDecimal = Money.ZERO
    private var close: BigDecimal = Money.ZERO
    private var lastTimestamp: Long = Long.MIN_VALUE
    private var worstDrawdown: BigDecimal = Money.ZERO

    /**
     * Folds one sample in. Equity is sampled before the tick that crosses a boundary, so a sample at exactly a
     * UTC midnight is the closing value of the day that just ended when the previous sample fell in that day:
     * the 23:00-24:00 hour belongs to its own day, a run ending at midnight (`--to 2026-10-15`) closes
     * 2026-10-14 there instead of reporting a 2026-10-15 it did not cover, and on daily bars, where every sample
     * is a midnight, each one closes the day the previous one began. A day that a midnight sample began opens
     * at that sample's equity. A midnight sample after a gap of days, or the run's first sample, opens its own
     * day as any other sample does.
     */
    fun accept(
        timestamp: Long,
        equity: BigDecimal,
    ) {
        val previous = lastTimestamp
        lastTimestamp = timestamp
        val day = Math.floorDiv(timestamp, MS_PER_DAY)
        val midnight = Math.floorMod(timestamp, MS_PER_DAY) == 0L
        val closesPrevious = midnight && previous != Long.MIN_VALUE && previous >= timestamp - MS_PER_DAY
        val target = if (closesPrevious) day - 1 else day
        if (target != currentDay) {
            if (currentDay != Long.MIN_VALUE && keepRows) days.add(current())
            val startsAtPrevious = previous != Long.MIN_VALUE && previous == target * MS_PER_DAY
            currentDay = target
            open = if (startsAtPrevious) close else equity
            high = open
            low = open
        }
        if (equity > high) high = equity
        if (equity < low) low = equity
        close = equity
        if (open.signum() > 0 && low < open) {
            val drawdown = open.subtract(low).divide(open, Money.CONTEXT)
            if (drawdown > worstDrawdown) worstDrawdown = drawdown
        }
    }

    /** Every completed day plus the day in progress, in order. Non-destructive. */
    fun result(): List<DailyEquity> = if (currentDay == Long.MIN_VALUE) emptyList() else days + current()

    /** The largest `(open - low) / open` of any day so far, on the same days as [result]. */
    fun maxDailyDrawdown(): BigDecimal = worstDrawdown.setScale(Money.SCALE, Money.ROUNDING)

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
