package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Kaufman Efficiency Ratio over the last [period] bars — how much of the price's total
 * travel actually went somewhere, in `[0, 1]`.
 *
 * `ER = |close - close[period bars ago]| / sum(|close[i] - close[i-1]|)`: the net
 * directional move divided by the total path length (the sum of every bar-to-bar step,
 * regardless of direction). A clean one-way trend covers ground efficiently, so ER is
 * near 1; choppy back-and-forth travel covers little net distance per step, so ER is near 0.
 *
 * This separates trend from noise in a way dispersion measures cannot: two windows can
 * share the same standard deviation yet have opposite efficiency — one a smooth drift, the
 * other a whipsaw that ends where it started.
 *
 * [period] counts bar-to-bar steps, so the indicator needs `period + 1` prices before it
 * reports. When the window is perfectly flat (no movement at all) ER is reported as 0.
 *
 * e.g. closes 100, 101, 102, 103 over period 3 → net 3, path 3 → ER 1.0; closes
 * 100, 102, 100, 102 → net 2, path 6 → ER ~0.33.
 */
class EfficiencyRatio(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 1) { "EfficiencyRatio.period must be > 1: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period + 1)

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = window.size >= period + 1

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > period + 1) window.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val net = window.last().subtract(window.first(), Money.CONTEXT).abs()
        var path = BigDecimal.ZERO
        var prev = window.first()
        var first = true
        for (p in window) {
            if (first) {
                first = false
            } else {
                path = path.add(p.subtract(prev, Money.CONTEXT).abs(), Money.CONTEXT)
                prev = p
            }
        }
        if (path.signum() == 0) return BigDecimal.ZERO.setScale(Money.SCALE, Money.ROUNDING)
        return net
            .divide(path, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
