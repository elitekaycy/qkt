package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal
import java.math.MathContext

data class BollingerBandValues(
    val upper: BigDecimal,
    val middle: BigDecimal,
    val lower: BigDecimal,
)

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
