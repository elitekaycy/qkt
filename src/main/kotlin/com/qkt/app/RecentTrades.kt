package com.qkt.app

import com.qkt.execution.Trade

/**
 * Bounded buffer of the most recent fills, feeding [LiveSessionHandle.recentTrades] and the
 * daemon status snapshot. Keeps the last [capacity] trades and silently drops older ones,
 * so a session running for months holds a fixed amount of trade history, not all of it.
 *
 * Thread-safety matches the session's usage: the engine thread adds, HTTP/status threads
 * snapshot. Both paths synchronize on this instance; adds are O(1) ring writes.
 */
class RecentTrades(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val ring = arrayOfNulls<Trade>(capacity)
    private var next = 0
    private var size = 0

    /** Records a fill, evicting the oldest retained trade once [capacity] is reached. */
    @Synchronized
    fun add(trade: Trade) {
        ring[next] = trade
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** Retained trades, oldest first (fill order). */
    @Synchronized
    fun snapshot(): List<Trade> {
        val out = ArrayList<Trade>(size)
        val start = if (size < capacity) 0 else next
        for (i in 0 until size) {
            out.add(ring[(start + i) % capacity]!!)
        }
        return out
    }

    companion object {
        /** Plenty for any daily status view; ~10k refs is a few hundred KB, not a leak. */
        const val DEFAULT_CAPACITY: Int = 10_000
    }
}
