package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Rolling z-score of the latest input over the last [period] values: how many sample
 * standard deviations the newest value sits from the window mean.
 *
 * z = (latest - mean) / stddev, with the sample (n-1) divisor matching [Stddev]. A z of +2
 * means the latest value is two standard deviations above its recent average — the canonical
 * mean-reversion / pairs-spread entry signal. Feed it a spread expression for pairs trading,
 * e.g. `ZSCORE(gold.close - 75 * silver.close, 60)`.
 *
 * Returns null until warmed up, and null when the window is flat (zero stddev — the z-score is
 * undefined), consistent with the "null = skip this bar" contract.
 *
 * O(period) per update, like [Stddev]; negligible at realistic periods.
 */
class ZScore(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 1) { "ZScore.period must be > 1 (needs a sample stddev): $period" }
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
        var sum = BigDecimal.ZERO
        for (v in window) sum = sum.add(v, Money.CONTEXT)
        val mean = sum.divide(BigDecimal(period), Money.CONTEXT)
        var ssd = BigDecimal.ZERO
        for (v in window) {
            val d = v.subtract(mean, Money.CONTEXT)
            ssd = ssd.add(d.multiply(d, Money.CONTEXT), Money.CONTEXT)
        }
        val stddev = ssd.divide(BigDecimal(period - 1), Money.CONTEXT).sqrt(Money.CONTEXT)
        if (stddev.signum() == 0) return null
        return window
            .last()
            .subtract(mean, Money.CONTEXT)
            .divide(stddev, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
