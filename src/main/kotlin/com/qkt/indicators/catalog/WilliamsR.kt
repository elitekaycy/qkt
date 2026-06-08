package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Williams %R — where the latest close sits within the high/low range of the last [period]
 * candles, scaled to [-100, 0].
 *
 * %R = -100 × (highestHigh − close) / (highestHigh − lowestLow). 0 = closing at the top of the
 * range (overbought), -100 = at the bottom (oversold). The inverse of fast Stochastic %K.
 *
 * Common use: an overbought/oversold filter — e.g. "only buy when %R < -80" (price near recent
 * lows). Returns null until [period] candles are seen; -100 when the window has no range.
 */
class WilliamsR(
    private val period: Int,
) : Indicator<Candle> {
    init {
        require(period > 0) { "WilliamsR.period must be > 0: $period" }
    }

    private val window: ArrayDeque<Candle> = ArrayDeque(period)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: Candle) {
        window.addLast(input)
        if (window.size > period) window.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val highestHigh = window.maxOf { it.high }
        val lowestLow = window.minOf { it.low }
        val range = highestHigh.subtract(lowestLow, Money.CONTEXT)
        if (range.signum() == 0) return BigDecimal("-100").setScale(Money.SCALE, Money.ROUNDING)
        val close = window.last().close
        return BigDecimal("-100")
            .multiply(highestHigh.subtract(close, Money.CONTEXT), Money.CONTEXT)
            .divide(range, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
