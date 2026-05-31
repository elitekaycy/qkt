package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Simple Moving Average over the last [period] input values. Equal weight to every
 * sample in the window; older samples drop out as new ones arrive.
 *
 * Slower to react than [EMA] of the same period but smoother. The cheapest indicator
 * to compute — O(1) per update once the window is full.
 */
class SMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private var sum: BigDecimal = BigDecimal.ZERO

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        sum = sum.add(input, Money.CONTEXT)
        if (window.size > period) {
            sum = sum.subtract(window.removeFirst(), Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        return sum
            .divide(BigDecimal(period), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
