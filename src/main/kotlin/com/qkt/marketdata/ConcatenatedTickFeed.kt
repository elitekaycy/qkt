package com.qkt.marketdata

/**
 * Plays [factories] sequentially — exhausts each feed before opening the next.
 *
 * Factories not instances so each feed is opened lazily and the next isn't created
 * until needed. Used to stitch together day-partitioned CSVs without holding them all
 * in memory.
 */
class ConcatenatedTickFeed(
    private val factories: List<() -> TickFeed>,
) : TickFeed {
    private var index = 0
    private var current: TickFeed? = null

    override fun next(): Tick? {
        while (index < factories.size) {
            val feed = current ?: factories[index]().also { current = it }
            val tick = feed.next()
            if (tick != null) return tick
            runCatching { feed.close() }
            current = null
            index++
        }
        return null
    }

    override fun close() {
        current?.let { runCatching { it.close() } }
        current = null
        index = factories.size
    }
}
