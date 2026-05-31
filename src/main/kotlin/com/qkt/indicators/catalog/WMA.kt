package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Weighted Moving Average — linear weights `1, 2, 3, …, period` from oldest to
 * newest sample, divided by `period(period+1)/2`. More responsive than [SMA] but
 * less so than [EMA]; the choice between the three is mostly trader-preference.
 */
class WMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private val denominator: BigDecimal =
        BigDecimal(period.toLong() * (period + 1) / 2)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > period) window.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        var weighted = BigDecimal.ZERO
        var weight = 1
        for (v in window) {
            weighted = weighted.add(BigDecimal(weight).multiply(v, Money.CONTEXT), Money.CONTEXT)
            weight++
        }
        return weighted
            .divide(denominator, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
