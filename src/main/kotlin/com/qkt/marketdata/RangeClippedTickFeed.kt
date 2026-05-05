package com.qkt.marketdata

class RangeClippedTickFeed(
    private val inner: TickFeed,
    private val fromMs: Long,
    private val toMs: Long,
) : TickFeed {
    override fun next(): Tick? {
        while (true) {
            val tick = inner.next() ?: return null
            if (tick.timestamp >= toMs) {
                inner.close()
                return null
            }
            if (tick.timestamp >= fromMs) return tick
        }
    }

    override fun close() = inner.close()
}
