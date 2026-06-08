package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.BiIndicator
import java.math.BigDecimal

/**
 * Rolling Pearson correlation between two series over the last [period] aligned pairs.
 *
 * r = Σ(x-x̄)(y-ȳ) / sqrt(Σ(x-x̄)² · Σ(y-ȳ)²), in [-1, 1]. The core pairs / stat-arb measure:
 * +1 = the two instruments move together, -1 = they move opposite, 0 = unrelated. e.g.
 * `CORRELATION(gold.close, silver.close, 60)`.
 *
 * Returns null until warmed up, and null when either series is flat over the window (zero
 * variance → correlation undefined), consistent with the "null = skip this bar" contract.
 *
 * O(period) per update.
 */
class Correlation(
    private val period: Int,
) : BiIndicator {
    init {
        require(period > 1) { "Correlation.period must be > 1: $period" }
    }

    private val xs: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private val ys: ArrayDeque<BigDecimal> = ArrayDeque(period)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = xs.size >= period

    override fun update(
        a: BigDecimal,
        b: BigDecimal,
    ) {
        xs.addLast(a)
        ys.addLast(b)
        if (xs.size > period) {
            xs.removeFirst()
            ys.removeFirst()
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val n = BigDecimal(period)
        var sx = BigDecimal.ZERO
        var sy = BigDecimal.ZERO
        for (i in 0 until period) {
            sx = sx.add(xs[i], Money.CONTEXT)
            sy = sy.add(ys[i], Money.CONTEXT)
        }
        val xMean = sx.divide(n, Money.CONTEXT)
        val yMean = sy.divide(n, Money.CONTEXT)
        var sxy = BigDecimal.ZERO
        var sxx = BigDecimal.ZERO
        var syy = BigDecimal.ZERO
        for (i in 0 until period) {
            val dx = xs[i].subtract(xMean, Money.CONTEXT)
            val dy = ys[i].subtract(yMean, Money.CONTEXT)
            sxy = sxy.add(dx.multiply(dy, Money.CONTEXT), Money.CONTEXT)
            sxx = sxx.add(dx.multiply(dx, Money.CONTEXT), Money.CONTEXT)
            syy = syy.add(dy.multiply(dy, Money.CONTEXT), Money.CONTEXT)
        }
        val denom = sxx.multiply(syy, Money.CONTEXT).sqrt(Money.CONTEXT)
        if (denom.signum() == 0) return null
        return sxy.divide(denom, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }
}
