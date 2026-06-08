package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hull Moving Average — a low-lag, smooth moving average built from weighted MAs:
 * raw = 2·WMA(period/2) − WMA(period), then HMA = WMA(raw) over √period bars.
 *
 * Tracks price closely with little of the lag a same-period [WMA]/[EMA] carries, while staying
 * smoother than [TEMA]. Common use as a faster trend line / crossover signal. On a constant
 * series it settles to that constant. Returns null until warmed up.
 */
class HMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 1) { "HMA.period must be > 1: $period" }
    }

    private val halfWma = WMA((period / 2).coerceAtLeast(1))
    private val fullWma = WMA(period)
    private val sqrtLen = sqrt(period.toDouble()).roundToInt().coerceAtLeast(1)
    private val hullWma = WMA(sqrtLen)
    private var count = 0
    private val two = BigDecimal(2)

    override val warmupBars: Int = period + sqrtLen - 1

    override val isReady: Boolean
        get() = count >= warmupBars

    override fun update(input: BigDecimal) {
        count++
        halfWma.update(input)
        fullWma.update(input)
        val half = halfWma.value()
        val full = fullWma.value()
        if (half != null && full != null) {
            val raw = two.multiply(half, Money.CONTEXT).subtract(full, Money.CONTEXT)
            hullWma.update(raw)
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        return hullWma.value()?.setScale(Money.SCALE, Money.ROUNDING)
    }
}
