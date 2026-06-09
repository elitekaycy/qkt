package com.qkt.backtest

import com.qkt.common.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Online worst-intraday-drawdown accumulator. For each UTC day it tracks the day-open equity and
 * the running intraday minimum; that day's drawdown is `(open − min) / open`. [maxDailyDrawdown] is
 * the largest such value across all days. Constant memory — no per-day or full-curve retention.
 *
 * e.g. a day opens at 100 and dips to 90 → 0.10 for that day; the worst day wins overall.
 */
class DailyDrawdownAccumulator {
    private var currentDay: Long = Long.MIN_VALUE
    private var dayOpen: BigDecimal = Money.ZERO
    private var dayMin: BigDecimal = Money.ZERO
    private var worst: BigDecimal = Money.ZERO

    fun accept(
        timestamp: Long,
        equity: BigDecimal,
    ) {
        val day =
            Instant
                .ofEpochMilli(timestamp)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toEpochDay()
        if (day != currentDay) {
            currentDay = day
            dayOpen = equity
            dayMin = equity
        }
        if (equity < dayMin) {
            dayMin = equity
            if (dayOpen.signum() > 0 && dayMin < dayOpen) {
                val dd = dayOpen.subtract(dayMin).divide(dayOpen, Money.CONTEXT)
                if (dd > worst) worst = dd
            }
        }
    }

    /** Worst intraday drawdown across all days seen so far, scaled to money precision. */
    fun maxDailyDrawdown(): BigDecimal = worst.setScale(Money.SCALE, Money.ROUNDING)
}
