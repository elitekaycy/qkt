package com.qkt.cli

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.math.RoundingMode

/** Bars between a book's rebalances: [durationMs] over the book's bar [window], rounded up; 0 when either is unset. */
internal fun rebalanceBars(
    durationMs: Long?,
    window: TimeWindow?,
): Int =
    durationMs?.let { duration ->
        window?.let { BigDecimal(duration).divide(BigDecimal(it.durationMs), 0, RoundingMode.CEILING).toInt() } ?: 0
    } ?: 0
