package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Rolling realized skewness of bar-to-bar returns over the last [period] returns — the
 * third standardized moment, a measure of how lopsided the recent return distribution is.
 *
 * Skewness answers "are the surprises mostly up or mostly down?". Negative skew means the
 * series has occasional sharp drops against many small gains (crash-prone); positive skew
 * means occasional sharp jumps up against many small losses (lottery-like). Standard
 * deviation only measures spread and cannot tell these two apart — skewness is what
 * separates them.
 *
 * Computed on **simple** returns `r = (p - p_prev) / p_prev`, using **population** moments
 * (divide by n, not n-1), so it is the textbook standardized skewness
 * `g1 = mean((r - mean)^3) / sigma^3`, where `sigma = sqrt(mean((r - mean)^2))`.
 *
 * [period] counts returns, so the indicator needs `period + 1` prices before it reports.
 * When every return in the window is identical (`sigma = 0`) the distribution has no shape,
 * so skewness is reported as 0.
 *
 * e.g. returns mostly small-positive with one large-negative → negative value; a symmetric
 * window → ~0.
 */
class Skew(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 2) { "Skew.period must be > 2 (third moment needs at least 3 returns): $period" }
    }

    private val returns: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private var prevPrice: BigDecimal? = null

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = returns.size >= period

    override fun update(input: BigDecimal) {
        val prev = prevPrice
        if (prev != null) {
            returns.addLast(input.subtract(prev, Money.CONTEXT).divide(prev, Money.CONTEXT))
            if (returns.size > period) returns.removeFirst()
        }
        prevPrice = input
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val n = BigDecimal(period)
        // Mean of returns.
        var sum = BigDecimal.ZERO
        for (r in returns) sum = sum.add(r, Money.CONTEXT)
        val mean = sum.divide(n, Money.CONTEXT)
        // Population second and third central moments.
        var m2 = BigDecimal.ZERO
        var m3 = BigDecimal.ZERO
        for (r in returns) {
            val d = r.subtract(mean, Money.CONTEXT)
            val d2 = d.multiply(d, Money.CONTEXT)
            m2 = m2.add(d2, Money.CONTEXT)
            m3 = m3.add(d2.multiply(d, Money.CONTEXT), Money.CONTEXT)
        }
        m2 = m2.divide(n, Money.CONTEXT)
        m3 = m3.divide(n, Money.CONTEXT)
        if (m2.signum() == 0) return BigDecimal.ZERO.setScale(Money.SCALE, Money.ROUNDING)
        val sigma = m2.sqrt(Money.CONTEXT)
        val sigma3 = sigma.multiply(sigma, Money.CONTEXT).multiply(sigma, Money.CONTEXT)
        return m3
            .divide(sigma3, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
