package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Rolling sample standard deviation over the last [period] input values.
 *
 * Sample (n-1) divisor rather than population (n) — matches the convention financial
 * tooling uses for empirical vol estimates from finite samples. For period N=20 on
 * close prices, this is the standard "20-bar volatility" estimator.
 *
 * Reusable primitive (#132 / strategy.txt follow-up). Used by:
 * - Vol-scaled position sizing (Strategy 1 momentum textbook spec)
 * - Z-score / mean reversion on any expression
 * - Risk-aware filters (e.g. "skip entries when recent vol > 2× its 60d average")
 * - Pairs / spread strategies via z-score on the spread
 *
 * O(period) per update — recomputes the mean and sum-of-squared-deviations each tick.
 * For typical periods (20-100), that's negligible at any realistic tick rate. A future
 * Welford's-algorithm rewrite could go O(1) per update if profiling ever shows it as
 * a hot spot, but the surface is the same.
 */
class Stddev(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 1) { "Stddev.period must be > 1 (sample stddev needs n-1 divisor): $period" }
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
        // Mean.
        var sum = BigDecimal.ZERO
        for (v in window) sum = sum.add(v, Money.CONTEXT)
        val mean = sum.divide(BigDecimal(period), Money.CONTEXT)
        // Sum of squared deviations.
        var ssd = BigDecimal.ZERO
        for (v in window) {
            val d = v.subtract(mean, Money.CONTEXT)
            ssd = ssd.add(d.multiply(d, Money.CONTEXT), Money.CONTEXT)
        }
        // Sample variance: ssd / (n - 1).
        val variance = ssd.divide(BigDecimal(period - 1), Money.CONTEXT)
        return variance
            .sqrt(Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
