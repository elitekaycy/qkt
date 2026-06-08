package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/** The two Stochastic lines: fast %K and its [Stochastic.dPeriod]-bar SMA, %D. */
data class StochasticLines(
    val k: BigDecimal,
    val d: BigDecimal,
)

/**
 * Fast Stochastic oscillator — where the close sits within the high/low range of the last
 * [kPeriod] candles (%K), and a [dPeriod]-bar moving average of that (%D), both in [0, 100].
 *
 * %K = 100 × (close − lowestLow) / (highestHigh − lowestLow); %D = SMA(%K, dPeriod). High %K
 * (near 100) = closing near the top of the recent range; low = near the bottom. The %K/%D
 * crossover is the classic entry trigger.
 *
 * Multi-output: the DSL exposes `STOCH_K` and `STOCH_D`. Returns null (via [lines]) until both
 * are warmed up; %K falls back to 50 (mid) when the window has no range.
 */
class Stochastic(
    private val kPeriod: Int,
    private val dPeriod: Int,
) : Indicator<Candle> {
    init {
        require(kPeriod > 0) { "Stochastic.kPeriod must be > 0: $kPeriod" }
        require(dPeriod > 0) { "Stochastic.dPeriod must be > 0: $dPeriod" }
    }

    private val window: ArrayDeque<Candle> = ArrayDeque(kPeriod)
    private val dSma: SMA = SMA(dPeriod)
    private var lastK: BigDecimal? = null

    override val warmupBars: Int = kPeriod + dPeriod - 1

    override val isReady: Boolean
        get() = lastK != null && dSma.isReady

    override fun update(input: Candle) {
        window.addLast(input)
        if (window.size > kPeriod) window.removeFirst()
        if (window.size < kPeriod) return
        val highestHigh = window.maxOf { it.high }
        val lowestLow = window.minOf { it.low }
        val range = highestHigh.subtract(lowestLow, Money.CONTEXT)
        val k =
            if (range.signum() == 0) {
                BigDecimal(50)
            } else {
                BigDecimal(100)
                    .multiply(input.close.subtract(lowestLow, Money.CONTEXT), Money.CONTEXT)
                    .divide(range, Money.CONTEXT)
            }
        lastK = k
        dSma.update(k)
    }

    /** The %K and %D lines, or null until both are computable. */
    fun lines(): StochasticLines? {
        val k = lastK ?: return null
        val d = dSma.value() ?: return null
        return StochasticLines(
            k = k.setScale(Money.SCALE, Money.ROUNDING),
            d = d.setScale(Money.SCALE, Money.ROUNDING),
        )
    }

    override fun value(): BigDecimal? = lines()?.k
}
