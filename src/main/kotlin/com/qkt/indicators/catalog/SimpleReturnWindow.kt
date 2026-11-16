package com.qkt.indicators.catalog

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * The last [size] simple bar-to-bar returns `r = (p - p_prev) / p_prev` of a price series, as
 * [Skew] and [VarianceRatio] consume them.
 *
 * A return off a zero or negative base price has no value (the division is undefined, and a
 * negative base is not a price). Such a return occupies its slot in [returns] as zero and the
 * window reports [isDefined] false until it has rolled out, so a consumer treats the window as
 * undefined input instead of computing on a hole or throwing.
 */
internal class SimpleReturnWindow(
    private val size: Int,
) {
    /** The returns in arrival order; an undefined return holds zero, see [isDefined]. */
    val returns: ArrayDeque<BigDecimal> = ArrayDeque(size)
    private val defined: ArrayDeque<Boolean> = ArrayDeque(size)
    private var undefinedCount = 0
    private var prevPrice: BigDecimal? = null

    /** Whether [size] returns have arrived. */
    val isFull: Boolean
        get() = returns.size >= size

    /** Whether every return in the window has a value. */
    val isDefined: Boolean
        get() = undefinedCount == 0

    /** Adds the return from the previous price to [price]; the first price only seeds. */
    fun push(price: BigDecimal) {
        val prev = prevPrice
        prevPrice = price
        if (prev == null) return
        val hasValue = prev.signum() > 0
        returns.addLast(
            if (hasValue) price.subtract(prev, Money.CONTEXT).divide(prev, Money.CONTEXT) else BigDecimal.ZERO,
        )
        defined.addLast(hasValue)
        if (!hasValue) undefinedCount++
        if (returns.size > size) {
            returns.removeFirst()
            if (!defined.removeFirst()) undefinedCount--
        }
    }
}
