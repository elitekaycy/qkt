package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Rolling-window minimum over the last [period] input values.
 *
 * Companion to [RollingHigh]. `lowest(close, N)` is the lower-half of the
 * Donchian channel — used for breakdown exits and short-side breakout entries.
 */
class RollingLow(
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
        var lo = window.first()
        for (v in window) if (v < lo) lo = v
        return lo
    }
}
