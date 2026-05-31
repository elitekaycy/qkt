package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * A moving average where the newest value counts most and the oldest counts
 * least, on a straight line.
 *
 * e.g. WMA(3) on `100, 102, 105` = `(1×100 + 2×102 + 3×105) / (1+2+3) = 103.5`.
 *
 * Sits between [SMA] (every value equal) and [EMA] (exponential decay) in how
 * fast it reacts. Choice between the three is mostly trader preference.
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
