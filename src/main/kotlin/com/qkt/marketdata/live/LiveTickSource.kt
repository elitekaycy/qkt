package com.qkt.marketdata.live

import com.qkt.marketdata.Tick

interface LiveTickSource {
    /**
     * Begin delivering ticks. [onDisconnect] fires when the underlying connection drops; [onReconnect]
     * fires when it comes back and resumes delivering ticks (default no-op for sources that do not yet
     * signal reconnection — they keep the terminate-on-disconnect behaviour).
     */
    fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit = {},
    )

    fun stop()
}
