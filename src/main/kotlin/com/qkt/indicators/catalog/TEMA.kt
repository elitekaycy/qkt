package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Triple Exponential Moving Average — lags even less than [DEMA] by combining three nested EMAs:
 * TEMA = 3·EMA − 3·EMA(EMA) + EMA(EMA(EMA)).
 *
 * The most responsive of the EMA family; favoured for fast trend-following at the cost of more
 * whipsaw in chop. On a constant series it settles to that constant (3c − 3c + c = c). Returns
 * null until warmed up.
 */
class TEMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "TEMA.period must be > 0: $period" }
    }

    private val ema1 = EMA(period)
    private val ema2 = EMA(period)
    private val ema3 = EMA(period)
    private var count = 0
    private val three = BigDecimal(3)

    override val warmupBars: Int = 3 * period - 2

    override val isReady: Boolean
        get() = count >= warmupBars

    override fun update(input: BigDecimal) {
        count++
        ema1.update(input)
        ema1.value()?.let { e1 ->
            ema2.update(e1)
            ema2.value()?.let { e2 -> ema3.update(e2) }
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val e1 = ema1.value() ?: return null
        val e2 = ema2.value() ?: return null
        val e3 = ema3.value() ?: return null
        return three
            .multiply(e1, Money.CONTEXT)
            .subtract(three.multiply(e2, Money.CONTEXT), Money.CONTEXT)
            .add(e3, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
