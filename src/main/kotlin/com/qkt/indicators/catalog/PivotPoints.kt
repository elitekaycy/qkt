package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

private const val MS_PER_DAY = 86_400_000L

/**
 * Classic floor-trader pivot levels derived from the prior completed UTC day's
 * high/low/close, held constant through the current day until the next day completes.
 *
 * These are the most-watched intraday reference levels on a market: because every desk
 * computes them the same way, resting take-profit and limit orders cluster at them, so
 * price is mechanically drawn back toward the central pivot and turns at the bands.
 *
 * - central pivot `P = (high + low + close) / 3`
 * - first resistance `R1 = 2 * P - low`
 * - first support `S1 = 2 * P - high`
 *
 * "Day" is the UTC calendar day (midnight to midnight), derived purely from each candle's
 * `startTime`, so the indicator is deterministic and reads identically in backtest and live.
 * Value is null until the first full day has completed and latched.
 *
 * e.g. yesterday H 110, L 90, C 105 → P = 101.67, R1 = 113.33, S1 = 91.67.
 */
class PivotPoints : Indicator<Candle> {
    /** The three latched levels for the current day, computed from the prior day's OHLC. */
    data class Levels(
        val p: BigDecimal,
        val r1: BigDecimal,
        val s1: BigDecimal,
    )

    private val three = BigDecimal(3)
    private val two = BigDecimal(2)

    private var activeDay: Long? = null
    private var curHigh: BigDecimal = BigDecimal.ZERO
    private var curLow: BigDecimal = BigDecimal.ZERO
    private var curClose: BigDecimal = BigDecimal.ZERO
    private var latchedHigh: BigDecimal? = null
    private var latchedLow: BigDecimal? = null
    private var latchedClose: BigDecimal? = null

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = latchedHigh != null

    override fun update(input: Candle) {
        val day = Math.floorDiv(input.startTime, MS_PER_DAY)
        if (day != activeDay) {
            if (activeDay != null) latch()
            activeDay = day
            curHigh = input.high
            curLow = input.low
            curClose = input.close
        } else {
            if (input.high > curHigh) curHigh = input.high
            if (input.low < curLow) curLow = input.low
            curClose = input.close
        }
    }

    private fun latch() {
        latchedHigh = curHigh
        latchedLow = curLow
        latchedClose = curClose
    }

    /** The latched pivot levels, or null until the first day has completed. */
    fun levels(): Levels? {
        val h = latchedHigh ?: return null
        val l = latchedLow ?: return null
        val c = latchedClose ?: return null
        val p = h.add(l, Money.CONTEXT).add(c, Money.CONTEXT).divide(three, Money.CONTEXT)
        val twoP = two.multiply(p, Money.CONTEXT)
        return Levels(
            p = p.setScale(Money.SCALE, Money.ROUNDING),
            r1 = twoP.subtract(l, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING),
            s1 = twoP.subtract(h, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING),
        )
    }

    override fun value(): BigDecimal? = levels()?.p
}
