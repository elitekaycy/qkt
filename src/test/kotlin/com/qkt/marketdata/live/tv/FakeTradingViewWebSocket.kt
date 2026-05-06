package com.qkt.marketdata.live.tv

import java.util.concurrent.CopyOnWriteArrayList

class FakeTradingViewWebSocket : TradingViewWebSocketLike {
    private val listeners: MutableList<TradingViewListener> = CopyOnWriteArrayList()

    val commandsSent: MutableList<Pair<String, List<Any>>> = mutableListOf()

    var closed: Boolean = false
        private set

    override fun addListener(listener: TradingViewListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TradingViewListener) {
        listeners.remove(listener)
    }

    override fun send(
        method: String,
        params: List<Any>,
    ) {
        commandsSent.add(method to params)
    }

    override fun close() {
        closed = true
    }

    fun replay(frames: Sequence<TradingViewFrame>) {
        frames.forEach { frame -> listeners.forEach { it.onFrame(frame) } }
    }

    fun simulateDisconnect(reason: String) {
        listeners.forEach { it.onDisconnected(reason) }
    }

    fun simulateConnect() {
        listeners.forEach { it.onConnected() }
    }
}
