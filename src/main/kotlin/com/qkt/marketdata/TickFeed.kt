package com.qkt.marketdata

interface TickFeed : AutoCloseable {
    fun next(): Tick?

    override fun close() {}
}
