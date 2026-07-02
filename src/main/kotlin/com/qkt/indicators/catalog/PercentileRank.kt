package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Rolling percentile rank: the fraction of the trailing [period]-bar window strictly
 * below the current value, in `[0, 1)`.
 *
 * Distribution-free — unlike a z-score it assumes nothing about the shape of the
 * distribution. On a bimodal series (e.g. a realized-vol series that splits into a
 * calm cluster and a hot cluster) `percentile_rank(...) < 0.5` cleanly selects the
 * lower-half regime, whereas a z-score's mean sits in the empty trough between the two
 * modes and its stddev is inflated by the hot mode, so it cannot separate them.
 *
 * e.g. window [10, 20, 30, 40, 25], current 25 → two values below → 2/5 = 0.4.
 */
class PercentileRank(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 1) { "PercentileRank.period must be > 1: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    // Computed once per update; value() is read once per referencing expression node per bar,
    // and each read re-counted the whole window.
    private var lastValue: BigDecimal? = null
    private val periodDivisor = BigDecimal(period)

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > period) window.removeFirst()
        lastValue =
            if (window.size >= period) {
                val current = window.last()
                var below = 0
                for (v in window) if (v < current) below++
                BigDecimal(below).divide(periodDivisor, Money.CONTEXT)
            } else {
                null
            }
    }

    override fun value(): BigDecimal? = lastValue
}
