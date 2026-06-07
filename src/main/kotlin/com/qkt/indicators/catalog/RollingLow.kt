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

    // Monotonic deque, increasing by value — the front is always the window minimum, so value() is
    // O(1) instead of an O(period) rescan. Each entry is (position, value); positions disambiguate
    // equal values so the correct one expires out of the window.
    private val mono: ArrayDeque<Pair<Long, BigDecimal>> = ArrayDeque()
    private var count: Long = 0L

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = count >= period

    override fun update(input: BigDecimal) {
        val pos = count
        count++
        while (mono.isNotEmpty() && mono.last().second >= input) mono.removeLast()
        mono.addLast(pos to input)
        val oldest = pos - period + 1
        while (mono.isNotEmpty() && mono.first().first < oldest) mono.removeFirst()
    }

    override fun value(): BigDecimal? = if (isReady) mono.first().second else null
}
