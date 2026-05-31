package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Average "true range" of a candle over the last [period] candles — a rolling
 * read on how much the price typically moves per bar. Higher ATR = wider swings.
 *
 * "True range" per candle = the largest of:
 *  - candle high − candle low (today's span)
 *  - |candle high − previous close| (today's high vs yesterday)
 *  - |candle low − previous close| (today's low vs yesterday)
 *
 * Smoothed with Wilder's averaging — the standard ATR convention.
 *
 * Common use: size stops to volatility. "Place SL at 2 × ATR" gives wider stops
 * when the market is rough and tighter stops when it's quiet, instead of a
 * fixed pip distance that doesn't adapt.
 */
class ATR(
    private val period: Int,
) : Indicator<Candle> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private var prevClose: BigDecimal? = null
    private val seedTrs: MutableList<BigDecimal> = ArrayList(period)
    private var atr: BigDecimal? = null

    private val periodBd: BigDecimal = BigDecimal(period)
    private val periodMinusOne: BigDecimal = BigDecimal(period - 1)

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = atr != null

    override fun update(input: Candle) {
        val prev = prevClose
        prevClose = input.close
        if (prev == null) return

        val hl = input.high.subtract(input.low, Money.CONTEXT)
        val hc = input.high.subtract(prev, Money.CONTEXT).abs()
        val lc = input.low.subtract(prev, Money.CONTEXT).abs()
        val tr = hl.max(hc).max(lc)

        val seeded = atr
        if (seeded == null) {
            seedTrs.add(tr)
            if (seedTrs.size == period) {
                var sum = BigDecimal.ZERO
                for (v in seedTrs) sum = sum.add(v, Money.CONTEXT)
                atr = sum.divide(periodBd, Money.CONTEXT)
                seedTrs.clear()
            }
        } else {
            atr =
                seeded
                    .multiply(periodMinusOne, Money.CONTEXT)
                    .add(tr, Money.CONTEXT)
                    .divide(periodBd, Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? = atr?.setScale(Money.SCALE, Money.ROUNDING)
}
