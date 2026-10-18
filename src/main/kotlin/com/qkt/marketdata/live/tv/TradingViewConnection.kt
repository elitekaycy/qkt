package com.qkt.marketdata.live.tv

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** How [TradingViewWebSocket] connects: the HTTP client it opens sockets with and its reconnect backoff. */
object TradingViewConnection {
    /** 1s, 2s, 4s ... doubling per failed attempt, capped at 30s. */
    fun backoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

    // No pingInterval: TradingView never answers a WebSocket ping, so OkHttp failed every connection
    // 40s in. Its liveness check is the `~h~` heartbeat frame, which TradingViewWebSocket echoes.
    internal fun defaultClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
}
