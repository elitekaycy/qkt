package com.qkt.marketdata

class ConcatenatedTickFeed(
    private val feeds: List<TickFeed>,
) : TickFeed {
    private var index = 0

    override fun next(): Tick? {
        while (index < feeds.size) {
            val tick = feeds[index].next()
            if (tick != null) return tick
            feeds[index].close()
            index++
        }
        return null
    }

    override fun close() {
        for (i in index until feeds.size) runCatching { feeds[i].close() }
    }
}
