package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Commodity Channel Index — how far the latest typical price is from its [period]-bar average,
 * scaled by the average absolute deviation.
 *
 * Typical price TP = (high + low + close) / 3. CCI = (TP − SMA(TP)) / (0.015 × meanDeviation),
 * where meanDeviation is the mean of |TP − SMA(TP)| over the window. The 0.015 constant makes
 * ~70-80% of values fall in [-100, 100]; readings beyond ±100 flag unusually stretched moves.
 *
 * Common use: mean-reversion (fade |CCI| > 100) or breakout (ride CCI crossing +100). Returns
 * null until [period] candles are seen; 0 when the window has no deviation.
 */
class CCI(
    private val period: Int,
) : Indicator<Candle> {
    init {
        require(period > 0) { "CCI.period must be > 0: $period" }
    }

    private val typicalPrices: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private val three: BigDecimal = BigDecimal(3)
    private val factor: BigDecimal = BigDecimal("0.015")

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = typicalPrices.size >= period

    override fun update(input: Candle) {
        val tp =
            input.high
                .add(input.low, Money.CONTEXT)
                .add(input.close, Money.CONTEXT)
                .divide(three, Money.CONTEXT)
        typicalPrices.addLast(tp)
        if (typicalPrices.size > period) typicalPrices.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        var sum = BigDecimal.ZERO
        for (tp in typicalPrices) sum = sum.add(tp, Money.CONTEXT)
        val mean = sum.divide(BigDecimal(period), Money.CONTEXT)
        var devSum = BigDecimal.ZERO
        for (tp in typicalPrices) devSum = devSum.add(tp.subtract(mean, Money.CONTEXT).abs(), Money.CONTEXT)
        val meanDev = devSum.divide(BigDecimal(period), Money.CONTEXT)
        if (meanDev.signum() == 0) return BigDecimal.ZERO.setScale(Money.SCALE, Money.ROUNDING)
        return typicalPrices
            .last()
            .subtract(mean, Money.CONTEXT)
            .divide(factor.multiply(meanDev, Money.CONTEXT), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
