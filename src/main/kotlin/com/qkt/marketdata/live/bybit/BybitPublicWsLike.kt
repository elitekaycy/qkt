package com.qkt.marketdata.live.bybit

/**
 * Abstraction over the Bybit public WebSocket transport. Production wires this to
 * OkHttp via [BybitPublicWs]; tests inject a [FakeBybitWebSocket] that drives frames
 * synchronously.
 */
interface BybitPublicWsLike {
    fun addListener(listener: BybitPublicListener)

    fun removeListener(listener: BybitPublicListener)

    fun send(text: String)

    fun close()
}

interface BybitPublicListener {
    fun onFrame(frame: BybitPublicFrame)

    fun onConnected()

    fun onDisconnected(reason: String)
}
