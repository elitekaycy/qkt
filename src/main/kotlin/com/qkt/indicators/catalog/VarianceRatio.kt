package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Lo-MacKinlay variance ratio over the last [lookback] bar-to-bar returns — a regime
 * statistic that separates mean-reversion from trending on the series' own path.
 *
 * Intuition: if returns were an unpredictable random walk, the variance of a [k]-bar move
 * would be exactly [k] times the variance of a 1-bar move (variance grows linearly with
 * time). The ratio of "actual k-bar variance" to "k times 1-bar variance" is therefore:
 * - `~1` — random walk, no exploitable autocorrelation;
 * - `< 1` — mean-reverting (overshoots partly retrace, so k-bar moves under-diffuse);
 * - `> 1` — trending (moves compound, so k-bar moves over-diffuse).
 *
 * Computed on **simple** returns `r = (p - p_prev) / p_prev` (consistent with [Skew], and
 * avoiding an inexact logarithm so backtest and live stay bit-identical). The k-bar
 * variance uses **overlapping** k-step sums of those returns; both variances are
 * **population** moments (divide by count). Formally
 * `VR = Var(sum of k consecutive returns) / (k * Var(single return))`.
 *
 * Needs [lookback] + 1 prices before it reports (one price seeds the first return). When
 * the 1-bar variance is zero (a perfectly flat or constant-return window) the ratio is
 * undefined and reported as null rather than a misleading number.
 *
 * e.g. returns +1%, -1%, +1%, -1% (k = 2) → every 2-bar sum is 0 → VR 0 (maximal
 * reversion); returns +1%, +1%, -1%, -1% (k = 2) → VR ~1.33 (short-run persistence).
 */
class VarianceRatio(
    private val k: Int,
    private val lookback: Int,
) : Indicator<BigDecimal> {
    init {
        require(k > 1) { "VarianceRatio.k must be > 1: $k" }
        require(lookback > k) {
            "VarianceRatio.lookback must be > k (need at least two overlapping k-bar windows): " +
                "lookback=$lookback k=$k"
        }
    }

    private val returns: ArrayDeque<BigDecimal> = ArrayDeque(lookback)
    private var prevPrice: BigDecimal? = null

    override val warmupBars: Int = lookback + 1

    override val isReady: Boolean
        get() = returns.size >= lookback

    override fun update(input: BigDecimal) {
        val prev = prevPrice
        if (prev != null) {
            returns.addLast(input.subtract(prev, Money.CONTEXT).divide(prev, Money.CONTEXT))
            if (returns.size > lookback) returns.removeFirst()
        }
        prevPrice = input
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val n1 = BigDecimal(lookback)
        var sum1 = BigDecimal.ZERO
        for (r in returns) sum1 = sum1.add(r, Money.CONTEXT)
        val mean1 = sum1.divide(n1, Money.CONTEXT)
        var var1 = BigDecimal.ZERO
        for (r in returns) {
            val d = r.subtract(mean1, Money.CONTEXT)
            var1 = var1.add(d.multiply(d, Money.CONTEXT), Money.CONTEXT)
        }
        var1 = var1.divide(n1, Money.CONTEXT)
        val denom = BigDecimal(k).multiply(var1, Money.CONTEXT)
        if (denom.signum() == 0) return null

        val numSums = lookback - k + 1
        val nk = BigDecimal(numSums)
        var sumK = BigDecimal.ZERO
        val sums = ArrayList<BigDecimal>(numSums)
        for (j in 0 until numSums) {
            var s = BigDecimal.ZERO
            for (i in 0 until k) s = s.add(returns[j + i], Money.CONTEXT)
            sums.add(s)
            sumK = sumK.add(s, Money.CONTEXT)
        }
        val meanK = sumK.divide(nk, Money.CONTEXT)
        var varK = BigDecimal.ZERO
        for (s in sums) {
            val d = s.subtract(meanK, Money.CONTEXT)
            varK = varK.add(d.multiply(d, Money.CONTEXT), Money.CONTEXT)
        }
        varK = varK.divide(nk, Money.CONTEXT)
        return varK
            .divide(denom, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
