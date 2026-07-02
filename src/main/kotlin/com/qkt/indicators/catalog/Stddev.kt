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
    private val periodDivisor = BigDecimal(period)
    private val sampleDivisor = BigDecimal(period - 1)

    // Computed once per update; the DSL reads value() once per referencing expression node
    // per bar, and recomputing the O(period) walk on every read was pure waste.
    private var lastValue: BigDecimal? = null

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > period) window.removeFirst()
        lastValue = if (window.size >= period) compute() else null
    }

    override fun value(): BigDecimal? = lastValue

    private fun compute(): BigDecimal {
        // Mean.
        var sum = BigDecimal.ZERO
        for (v in window) sum = sum.add(v, Money.CONTEXT)
        val mean = sum.divide(periodDivisor, Money.CONTEXT)
        // Sum of squared deviations.
        var ssd = BigDecimal.ZERO
        for (v in window) {
            val d = v.subtract(mean, Money.CONTEXT)
            ssd = ssd.add(d.multiply(d, Money.CONTEXT), Money.CONTEXT)
        }
        // Sample variance: ssd / (n - 1).
        val variance = ssd.divide(sampleDivisor, Money.CONTEXT)
        return variance
            .sqrt(Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
