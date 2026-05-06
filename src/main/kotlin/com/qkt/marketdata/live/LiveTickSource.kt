package com.qkt.marketdata.live

import com.qkt.marketdata.Tick

interface LiveTickSource {
    fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
    )

    fun stop()
}
