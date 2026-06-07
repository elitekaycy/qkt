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

    // Monotonic deque, decreasing by value — the front is always the window maximum, so value() is
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
        while (mono.isNotEmpty() && mono.last().second <= input) mono.removeLast()
        mono.addLast(pos to input)
        val oldest = pos - period + 1
        while (mono.isNotEmpty() && mono.first().first < oldest) mono.removeFirst()
    }

    override fun value(): BigDecimal? = if (isReady) mono.first().second else null
}
