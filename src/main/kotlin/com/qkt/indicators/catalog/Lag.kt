package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * The value of the input series as it stood [n] bars ago — a pure time offset.
 *
 * `lag(series, n)` reports `series[t - n]`: fed one value per bar, it returns the value
 * from n bars back and holds nothing else. This is the missing piece for any
 * "skip the recent window" construction. e.g. an intermediate-horizon trend that
 * deliberately excludes the latest month is the sign of `lag(close, 21) - lag(close, 252)` —
 * a return that ends 21 bars in the past and starts 252 bars back.
 *
 * The reported value is the buffered input verbatim (no arithmetic), so it is exact and
 * carries the input's own scale. Null until [n] + 1 bars have been seen.
 *
 * e.g. fed 100, 101, 102, 103 with n = 2 → after the 4th bar reports 101 (two bars back).
 */
class Lag(
    private val n: Int,
) : Indicator<BigDecimal> {
    init {
        require(n > 0) { "Lag.n must be > 0: $n" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(n + 1)

    override val warmupBars: Int = n + 1

    override val isReady: Boolean
        get() = window.size >= n + 1

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > n + 1) window.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        return window.first()
    }
}
