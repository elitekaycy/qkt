package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Double Exponential Moving Average — a moving average that lags less than a plain [EMA] by
 * subtracting the EMA-of-the-EMA: DEMA = 2·EMA − EMA(EMA).
 *
 * Reacts faster to price turns than an EMA of the same period, at the cost of more overshoot.
 * Common use anywhere an EMA is used but more responsiveness is wanted. On a constant series it
 * settles to that constant (2c − c = c). Returns null until warmed up.
 */
class DEMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "DEMA.period must be > 0: $period" }
    }

    private val ema1 = EMA(period)
    private val ema2 = EMA(period)
    private var count = 0
    private val two = BigDecimal(2)

    override val warmupBars: Int = 2 * period - 1

    override val isReady: Boolean
        get() = count >= warmupBars

    override fun update(input: BigDecimal) {
        count++
        ema1.update(input)
        ema1.value()?.let { ema2.update(it) }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val e1 = ema1.value() ?: return null
        val e2 = ema2.value() ?: return null
        return two.multiply(e1, Money.CONTEXT).subtract(e2, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }
}
