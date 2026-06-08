package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Rolling sample variance over the last [period] values — the square of [Stddev], exposed
 * directly for callers that want the un-rooted dispersion (e.g. variance-ratio tests, or
 * combining variances additively).
 *
 * Sample (n-1) divisor, matching [Stddev]. O(period) per update. Returns null until warmed up.
 */
class Variance(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 1) { "Variance.period must be > 1 (sample variance needs n-1): $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > period) window.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        var sum = BigDecimal.ZERO
        for (v in window) sum = sum.add(v, Money.CONTEXT)
        val mean = sum.divide(BigDecimal(period), Money.CONTEXT)
        var ssd = BigDecimal.ZERO
        for (v in window) {
            val d = v.subtract(mean, Money.CONTEXT)
            ssd = ssd.add(d.multiply(d, Money.CONTEXT), Money.CONTEXT)
        }
        return ssd.divide(BigDecimal(period - 1), Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }
}
