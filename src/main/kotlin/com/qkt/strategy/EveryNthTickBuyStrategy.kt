package com.qkt.strategy

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.math.BigDecimal

class EveryNthTickBuyStrategy(
    private val symbol: String,
    private val n: Int = 10,
    private val size: BigDecimal = Money.of("1"),
) : Strategy {
    init {
        require(n > 0) { "n must be > 0: $n" }
        require(size.signum() > 0) { "size must be > 0: $size" }
    }

    private var counter = 0

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (tick.symbol != symbol) return
        counter++
        if (counter % n == 0) emit(Signal.Buy(symbol, size))
    }
}
