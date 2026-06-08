package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.BiIndicator
import java.math.BigDecimal

/**
 * Rolling beta of a dependent series against an independent ("market") series over the last
 * [period] aligned pairs: how much the first series moves per unit move of the second.
 *
 * beta = Cov(a, b) / Var(b), with `a` the dependent series and `b` the independent one. e.g.
 * `BETA(asset.close, market.close, 60)` — beta of 1.5 means the asset moves ~1.5× the market.
 * Used for hedge ratios and market-neutral sizing.
 *
 * Returns null until warmed up, and null when the independent series is flat (zero variance →
 * beta undefined). O(period) per update.
 */
class Beta(
    private val period: Int,
) : BiIndicator {
    init {
        require(period > 1) { "Beta.period must be > 1: $period" }
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
        var syy = BigDecimal.ZERO
        for (i in 0 until period) {
            val dx = xs[i].subtract(xMean, Money.CONTEXT)
            val dy = ys[i].subtract(yMean, Money.CONTEXT)
            sxy = sxy.add(dx.multiply(dy, Money.CONTEXT), Money.CONTEXT)
            syy = syy.add(dy.multiply(dy, Money.CONTEXT), Money.CONTEXT)
        }
        // Cov(a,b)/Var(b) — the (n-1) divisors cancel, so use the raw sums of products.
        if (syy.signum() == 0) return null
        return sxy.divide(syy, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }
}
