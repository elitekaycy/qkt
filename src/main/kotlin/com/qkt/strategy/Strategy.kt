package com.qkt.strategy

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(
        tick: Tick,
        emit: (Signal) -> Unit,
    )

    fun onTickWithContext(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
        onTick(tick, emit)
    }

    fun onCandle(
        candle: Candle,
        emit: (Signal) -> Unit,
    ) {}
}
