package com.qkt.marketdata

/**
 * Filters [inner] to ticks in `[fromMs, toMs)`. Stops early once `toMs` is reached.
 *
 * Used by walk-forward and parameter-sweep harnesses to slice a master feed into
 * train/test windows without re-reading the CSV.
 */
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
