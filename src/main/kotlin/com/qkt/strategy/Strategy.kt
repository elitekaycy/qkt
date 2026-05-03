package com.qkt.strategy

import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(
        tick: Tick,
        emit: (Signal) -> Unit,
    )
}
