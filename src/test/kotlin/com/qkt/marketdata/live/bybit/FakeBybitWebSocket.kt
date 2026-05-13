package com.qkt.marketdata.live.bybit

import java.util.concurrent.CopyOnWriteArrayList

class FakeBybitWebSocket : BybitPublicWsLike {
    private val listeners: MutableList<BybitPublicListener> = CopyOnWriteArrayList()

    val sentTexts: MutableList<String> = mutableListOf()

    var closed: Boolean = false
        private set

    override fun addListener(listener: BybitPublicListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: BybitPublicListener) {
        listeners.remove(listener)
    }

    override fun send(text: String) {
        sentTexts.add(text)
    }

    override fun close() {
        closed = true
    }

    fun deliver(text: String) {
        val frame = BybitPublicFrame.parse(text)
        listeners.forEach { it.onFrame(frame) }
    }

    fun simulateConnect() {
        listeners.forEach { it.onConnected() }
    }

    fun simulateDisconnect(reason: String) {
        listeners.forEach { it.onDisconnected(reason) }
    }
}
