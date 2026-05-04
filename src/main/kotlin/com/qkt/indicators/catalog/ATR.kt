package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

class ATR(
    private val period: Int,
) : Indicator<Candle> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private var prevClose: BigDecimal? = null
    private val seedTrs: MutableList<BigDecimal> = ArrayList(period)
    private var atr: BigDecimal? = null

    private val periodBd: BigDecimal = BigDecimal(period)
    private val periodMinusOne: BigDecimal = BigDecimal(period - 1)

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = atr != null

    override fun update(input: Candle) {
        val prev = prevClose
        prevClose = input.close
        if (prev == null) return

        val hl = input.high.subtract(input.low, Money.CONTEXT)
        val hc = input.high.subtract(prev, Money.CONTEXT).abs()
        val lc = input.low.subtract(prev, Money.CONTEXT).abs()
        val tr = hl.max(hc).max(lc)

        val seeded = atr
        if (seeded == null) {
            seedTrs.add(tr)
            if (seedTrs.size == period) {
                var sum = BigDecimal.ZERO
                for (v in seedTrs) sum = sum.add(v, Money.CONTEXT)
                atr = sum.divide(periodBd, Money.CONTEXT)
                seedTrs.clear()
            }
        } else {
            atr =
                seeded
                    .multiply(periodMinusOne, Money.CONTEXT)
                    .add(tr, Money.CONTEXT)
                    .divide(periodBd, Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? = atr?.setScale(Money.SCALE, Money.ROUNDING)
}
