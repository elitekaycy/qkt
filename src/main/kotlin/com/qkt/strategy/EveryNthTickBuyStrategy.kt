package com.qkt.strategy

import com.qkt.marketdata.Tick

class EveryNthTickBuyStrategy(
    private val symbol: String,
    private val n: Int = 10,
    private val size: Double = 1.0,
) : Strategy {
    init {
        require(n > 0) { "n must be > 0: $n" }
        require(size > 0.0) { "size must be > 0: $size" }
    }

    private var counter = 0

    override fun onTick(
        tick: Tick,
        emit: (Signal) -> Unit,
    ) {
        if (tick.symbol != symbol) return
        counter++
        if (counter % n == 0) emit(Signal.Buy(symbol, size))
    }
}
