package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal
import java.math.MathContext

/** Three-band Bollinger reading — `middle` is the running average, `upper`/`lower` are the rails above and below. */
data class BollingerBandValues(
    val upper: BigDecimal,
    val middle: BigDecimal,
    val lower: BigDecimal,
)

/**
 * Bollinger Bands: a moving average with two "rails" above and below that widen
 * when prices get jumpy and narrow when prices get quiet.
 *
 * The middle rail is an [SMA] of the last [period] closes. The upper and lower
 * rails sit [stddevK] times the recent standard deviation above/below — so the
 * rails breathe with volatility instead of staying a fixed distance away.
 *
 * Common reads:
 *  - **price touches upper rail** — stretched up; mean-reversion traders fade
 *  - **price touches lower rail** — stretched down; mean-reversion traders buy
 *  - **rails narrow** — quiet market; often precedes a breakout
 *
 * Defaults: 20-period SMA with 2σ rails (the original 1980s recipe). Use 1σ for
 * tighter, more frequent signals; 3σ for rarer, more extreme ones.
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
