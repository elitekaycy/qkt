package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * A moving average that gives more weight to recent values than older ones — so
 * it reacts to new prices faster than [SMA] of the same [period].
 *
 * e.g. EMA(9) on closes `100, 105, 110` weights the 110 more than the 100. The
 * exact formula: `new = old + α(value − old)` with `α = 2 / (period + 1)`.
 *
 * Common use: trend filters (`ema(close, 9) > ema(close, 21)` = short-term trend
 * is up). The first reading is just the first input — no warmup pre-fill — which
 * matches what charting platforms like TradingView show.
 */
class EMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val alpha: BigDecimal =
        BigDecimal(2).divide(BigDecimal(period + 1), Money.CONTEXT)
    private val oneMinusAlpha: BigDecimal =
        BigDecimal.ONE.subtract(alpha, Money.CONTEXT)

    private val seedBuffer: MutableList<BigDecimal> = ArrayList(period)
    private var ema: BigDecimal? = null

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = ema != null

    override fun update(input: BigDecimal) {
        val prev = ema
        if (prev == null) {
            seedBuffer.add(input)
            if (seedBuffer.size == period) {
                var sum = BigDecimal.ZERO
                for (v in seedBuffer) sum = sum.add(v, Money.CONTEXT)
                ema = sum.divide(BigDecimal(period), Money.CONTEXT)
                seedBuffer.clear()
            }
        } else {
            ema =
                alpha
                    .multiply(input, Money.CONTEXT)
                    .add(oneMinusAlpha.multiply(prev, Money.CONTEXT), Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? = ema?.setScale(Money.SCALE, Money.ROUNDING)
}
