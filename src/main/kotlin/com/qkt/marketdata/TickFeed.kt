package com.qkt.marketdata

interface TickFeed {
    fun next(): Tick?
}
