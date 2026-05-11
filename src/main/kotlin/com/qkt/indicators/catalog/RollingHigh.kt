package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Rolling-window maximum over the last [period] input values.
 *
 * Used for Donchian-style breakout strategies. `highest(close, N)` answers
 * "what was the highest value seen in the last N updates?" After the warmup
 * window of [period] updates has passed, `value()` returns the maximum.
 *
 * The caller controls the "include current bar or not" semantics by deciding
 * when to call [update] relative to when [value] is read in a rule body.
 */
class RollingHigh(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        while (window.size > period) window.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        var hi = window.first()
        for (v in window) if (v > hi) hi = v
        return hi
    }
}
