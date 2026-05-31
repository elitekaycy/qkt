package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * A running average of the last [period] values you feed it. Every value counts
 * equally; the oldest one drops out each time a new one arrives.
 *
 * e.g. SMA(3) after seeing `100, 102, 105, 108` returns `(102+105+108) / 3 = 105`.
 *
 * Common use: "is the price above or below its recent average?" A 20-day SMA on
 * closes is a classic trend reference. Slower to move than [EMA] of the same
 * period — same data, but EMA reacts to fresh values faster.
 */
class SMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private var sum: BigDecimal = BigDecimal.ZERO

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        sum = sum.add(input, Money.CONTEXT)
        if (window.size > period) {
            sum = sum.subtract(window.removeFirst(), Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        return sum
            .divide(BigDecimal(period), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
