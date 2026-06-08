package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Slope of the least-squares regression line through the last [period] values, fit against
 * their position in the window (x = 0, 1, … period-1, oldest to newest).
 *
 * A positive slope means the series trends up over the window; the magnitude is the average
 * change in value per bar. e.g. on a clean ramp 0,1,2,3,4 the slope is 1.0. Useful as a
 * trend-strength filter or a smoothed momentum reading.
 *
 * slope = Σ(i - x̄)(y - ȳ) / Σ(i - x̄)². Returns null until warmed up. O(period) per update.
 */
class RegressionSlope(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 1) { "RegressionSlope.period must be > 1: $period" }
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
        // x runs 0..period-1, so its mean is (period - 1) / 2.
        val xMean = BigDecimal(period - 1).divide(BigDecimal(2), Money.CONTEXT)
        var yMean = BigDecimal.ZERO
        for (v in window) yMean = yMean.add(v, Money.CONTEXT)
        yMean = yMean.divide(BigDecimal(period), Money.CONTEXT)
        var sxy = BigDecimal.ZERO
        var sxx = BigDecimal.ZERO
        var i = 0
        for (v in window) {
            val dx = BigDecimal(i).subtract(xMean, Money.CONTEXT)
            val dy = v.subtract(yMean, Money.CONTEXT)
            sxy = sxy.add(dx.multiply(dy, Money.CONTEXT), Money.CONTEXT)
            sxx = sxx.add(dx.multiply(dx, Money.CONTEXT), Money.CONTEXT)
            i++
        }
        if (sxx.signum() == 0) return null
        return sxy.divide(sxx, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }
}
