package com.qkt.strategy

import com.qkt.common.Money
import com.qkt.indicators.catalog.EMA
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

class EmaCrossoverStrategy(
    private val symbol: String,
    private val fastPeriod: Int = 9,
    private val slowPeriod: Int = 21,
    private val size: BigDecimal = Money.of("1"),
) : Strategy {
    init {
        require(fastPeriod > 0) { "fastPeriod must be > 0: $fastPeriod" }
        require(slowPeriod > fastPeriod) {
            "slowPeriod must be > fastPeriod: slowPeriod=$slowPeriod, fastPeriod=$fastPeriod"
        }
        require(size.signum() > 0) { "size must be > 0: $size" }
    }

    private val fast = EMA(fastPeriod)
    private val slow = EMA(slowPeriod)
    private var lastFastAboveSlow: Boolean? = null

    override fun onTick(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
    }

    override fun onCandle(
        candle: Candle,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
        if (candle.symbol != symbol) return
        fast.update(candle.close)
        slow.update(candle.close)
        if (!fast.isReady || !slow.isReady) return

        val fastAbove = fast.value()!! > slow.value()!!
        val prev = lastFastAboveSlow
        lastFastAboveSlow = fastAbove
        if (prev == null) return

        if (!prev && fastAbove) emit(Signal.Buy(symbol, size))
        if (prev && !fastAbove) emit(Signal.Sell(symbol, size))
    }
}
