package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal
import java.math.MathContext

/** Three-band Bollinger reading — middle is the SMA, upper/lower are [stddevK] σ above/below. */
data class BollingerBandValues(
    val upper: BigDecimal,
    val middle: BigDecimal,
    val lower: BigDecimal,
)

/**
 * Bollinger Bands: a moving average plus a volatility envelope [stddevK] standard
 * deviations wide on each side. [Indicator.value] returns the middle (SMA); use
 * [bands] for the full three-band reading.
 *
 * Defaults to the classic 20-period, 2σ shape; other widths are common (e.g. 1σ for
 * tighter mean-reversion entries).
 */
class BollingerBands(
    private val period: Int,
    private val stddevK: Double = 2.0,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
        require(stddevK >= 0.0) { "stddevK must be >= 0: $stddevK" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private val k: BigDecimal = BigDecimal(stddevK.toString())
    private val periodBd: BigDecimal = BigDecimal(period)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > period) window.removeFirst()
    }

    override fun value(): BigDecimal? = bands()?.middle

    fun bands(): BollingerBandValues? {
        if (!isReady) return null
        var sum = BigDecimal.ZERO
        for (v in window) sum = sum.add(v, MathContext.DECIMAL128)
        val mean = sum.divide(periodBd, MathContext.DECIMAL128)
        var variance = BigDecimal.ZERO
        for (v in window) {
            val d = v.subtract(mean, MathContext.DECIMAL128)
            variance = variance.add(d.multiply(d, MathContext.DECIMAL128), MathContext.DECIMAL128)
        }
        variance = variance.divide(periodBd, MathContext.DECIMAL128)
        val stddev = variance.sqrt(Money.CONTEXT)
        val offset = k.multiply(stddev, Money.CONTEXT)
        return BollingerBandValues(
            upper = mean.add(offset, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING),
            middle = mean.setScale(Money.SCALE, Money.ROUNDING),
            lower = mean.subtract(offset, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING),
        )
    }
}
