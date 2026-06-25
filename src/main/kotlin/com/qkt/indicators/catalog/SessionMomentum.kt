package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

private const val MS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24L
private const val MS_PER_DAY = 86_400_000L

/**
 * Cumulative momentum measured over ONLY the bars inside a recurring intraday UTC window
 * `[startHour, endHour)`, accumulated across the last [nDays] completed days.
 *
 * Some edges live in one part of the day — e.g. the London/NY overlap carries the macro
 * repricing while the quiet Asian hours inject mean-reverting noise. A momentum estimate run
 * over all 24 hours dilutes the in-window drift with that noise. This isolates it: each day
 * contributes its within-window simple return `(last in-window close / first in-window open) - 1`,
 * and the indicator reports the sum of those daily returns over the last [nDays] *completed*
 * days. The currently forming day is excluded, so the value is stable to read at the window
 * open. Positive means the in-window segment has been drifting up.
 *
 * "Day" is the UTC calendar day, derived purely from each candle's `startTime`, so the
 * indicator is deterministic across backtest and live. Value is null until [nDays] in-window
 * days have completed.
 *
 * e.g. window 12:00–14:00 UTC, nDays 2; yesterday's 12–14 segment returned +0.5% and the day
 * before +0.2% → reports +0.7%.
 */
class SessionMomentum(
    private val startHour: Int,
    private val endHour: Int,
    private val nDays: Int,
) : Indicator<Candle> {
    init {
        require(startHour in 0..23 && endHour in 0..23) { "SessionMomentum hours must be in 0..23" }
        require(startHour < endHour) { "SessionMomentum window must not wrap midnight: $startHour..$endHour" }
        require(nDays > 0) { "SessionMomentum.nDays must be > 0: $nDays" }
    }

    private val dailyReturns: ArrayDeque<BigDecimal> = ArrayDeque(nDays)
    private var activeDay: Long? = null
    private var sessionOpen: BigDecimal? = null
    private var sessionClose: BigDecimal? = null

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = dailyReturns.size >= nDays

    override fun update(input: Candle) {
        val hour = Math.floorMod(Math.floorDiv(input.startTime, MS_PER_HOUR), HOURS_PER_DAY).toInt()
        if (hour < startHour || hour >= endHour) return
        val day = Math.floorDiv(input.startTime, MS_PER_DAY)
        if (day != activeDay) {
            finalizeSession()
            activeDay = day
            sessionOpen = input.open
            sessionClose = input.close
        } else {
            sessionClose = input.close
        }
    }

    private fun finalizeSession() {
        val open = sessionOpen ?: return
        val close = sessionClose ?: return
        dailyReturns.addLast(close.subtract(open, Money.CONTEXT).divide(open, Money.CONTEXT))
        if (dailyReturns.size > nDays) dailyReturns.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        var sum = BigDecimal.ZERO
        for (r in dailyReturns) sum = sum.add(r, Money.CONTEXT)
        return sum.setScale(Money.SCALE, Money.ROUNDING)
    }
}
