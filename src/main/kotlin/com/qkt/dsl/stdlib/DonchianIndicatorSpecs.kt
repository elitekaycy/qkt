package com.qkt.dsl.stdlib

import com.qkt.indicators.Indicator
import com.qkt.indicators.catalog.RollingHigh
import com.qkt.indicators.catalog.RollingLow
import java.math.BigDecimal

/**
 * Registry entries for the Donchian rolling extremes HIGHEST and LOWEST, lagged one bar
 * through [PriorBars] so a breakout compares against the bars before the current one.
 */
internal val donchianIndicatorSpecs: List<Pair<String, IndicatorSpec>> =
    listOf(
        // ---- Donchian rolling extremes ----
        // Rules evaluate after the closing bar has already been pushed into every
        // indicator (update-then-fire), so a raw rolling extreme would include the
        // current bar and `close > highest(close, N)` could never be true. The
        // one-bar lag makes HIGHEST/LOWEST cover the N bars BEFORE the current one,
        // matching the documented breakout semantics (reference/dsl/indicators.md).
        "HIGHEST" to
            IndicatorSpec("HIGHEST", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                PriorBars(RollingHigh(period = args[0].toInt()))
            },
        "LOWEST" to
            IndicatorSpec("LOWEST", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                PriorBars(RollingLow(period = args[0].toInt()))
            },
    )

/**
 * Reports [inner]'s value as it stood BEFORE the most recent update — i.e. over
 * the window of bars prior to the current one. e.g. a RollingHigh(3) fed
 * 1, 2, 3, then 9: the raw indicator says max(2, 3, 9) = 9, so `close > highest`
 * can never fire; through this lag it says max(1, 2, 3) = 3, and 9 > 3 fires.
 */
private class PriorBars(
    private val inner: Indicator<BigDecimal>,
) : Indicator<BigDecimal> {
    private var prev: BigDecimal? = null
    private var prevReady = false

    override fun update(input: BigDecimal) {
        prev = inner.value()
        prevReady = inner.isReady
        inner.update(input)
    }

    override fun value(): BigDecimal? = prev

    override val isReady: Boolean get() = prevReady

    override val warmupBars: Int = inner.warmupBars + 1
}
