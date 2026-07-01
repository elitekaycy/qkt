package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * The signed length of the current run of same-direction changes in a series — a streak counter.
 *
 * Each update compares the value to the previous one and extends, flips, or breaks the streak:
 * a positive value is an up-streak of that many consecutive rises, a negative value is a
 * down-streak, and zero means the last change was flat (an unchanged value breaks the run).
 * There is no lookback window; the streak accumulates from the last direction change.
 *
 * e.g. closes 100, 101, 102, 103 → +3 (three consecutive rises); a following 101 flips it to
 * -1; a following 101 again (unchanged) breaks it to 0.
 *
 * Value is null until the first change is seen (one prior value is needed). Deterministic across
 * backtest and live. Fed the close series it counts a daily-close run; fed any expression it
 * counts that expression's run.
 */
class RunLength : Indicator<BigDecimal> {
    private var prev: BigDecimal? = null
    private var run: Int = 0
    private var ready = false

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = ready

    override fun update(input: BigDecimal) {
        val last = prev
        if (last != null) {
            val sign = input.compareTo(last) // -1, 0, or +1
            run =
                when {
                    sign == 0 -> 0
                    (run > 0 && sign > 0) || (run < 0 && sign < 0) -> run + sign
                    else -> sign
                }
            ready = true
        }
        prev = input
    }

    override fun value(): BigDecimal? = if (ready) BigDecimal(run) else null
}
