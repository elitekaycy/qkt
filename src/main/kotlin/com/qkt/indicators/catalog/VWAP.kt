package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.MathContext

/**
 * Volume-Weighted Average Price over the last [period] ticks.
 *
 * Updates on raw [Tick]s, not on candle close — the binding layer routes ticks
 * directly so VWAP sees intra-bar resolution. Use it as a fair-value benchmark
 * ("am I buying above or below the recent volume-weighted price?").
 */
class VWAP(
    private val period: Int,
) : Indicator<Tick> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private data class Sample(
        val priceVol: BigDecimal,
        val volume: BigDecimal,
    )

    private val window: ArrayDeque<Sample> = ArrayDeque(period)
    private var numerator: BigDecimal = BigDecimal.ZERO
    private var denominator: BigDecimal = BigDecimal.ZERO

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: Tick) {
        val volume =
            input.volume
                ?: error("VWAP requires non-null Tick.volume; got null for ${input.symbol} @ ${input.timestamp}")
        val pv = input.price.multiply(volume, MathContext.DECIMAL128)
        window.addLast(Sample(pv, volume))
        numerator = numerator.add(pv, MathContext.DECIMAL128)
        denominator = denominator.add(volume, MathContext.DECIMAL128)
        if (window.size > period) {
            val out = window.removeFirst()
            numerator = numerator.subtract(out.priceVol, MathContext.DECIMAL128)
            denominator = denominator.subtract(out.volume, MathContext.DECIMAL128)
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        if (denominator.signum() == 0) return null
        return numerator
            .divide(denominator, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
