package com.qkt.strategy

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    )

    fun onCandle(
        candle: Candle,
        emit: (Signal) -> Unit,
    ) {}
}
