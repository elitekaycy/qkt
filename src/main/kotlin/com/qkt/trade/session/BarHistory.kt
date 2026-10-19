package com.qkt.trade.session

import com.qkt.marketdata.Candle

/**
 * Bounded per-symbol buffer of closed candles for a bot run session.
 *
 * A session records every closed bar here (via [com.qkt.trade.session.BotSessionRecorder])
 * so `bot bars`/`bot next` can be answered from session truth at the replay cursor —
 * never from the venue, which in a backtest would not exist. Retains the newest
 * [capacity] bars per symbol; [countFor] is the monotonic total ever seen, which the
 * session uses as its per-stream bar clock (e.g. "advance replay until countFor > n").
 */
class BarHistory(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "BarHistory capacity must be positive, got $capacity" }
    }

    private val bars = mutableMapOf<String, ArrayDeque<Candle>>()
    private val counts = mutableMapOf<String, Long>()

    /** Appends one closed candle under its symbol, evicting the oldest past [capacity]. O(1). */
    fun record(candle: Candle) = record(candle.symbol, candle)

    /** Appends one closed candle under [key] (a symbol, or a series `symbol@tf`). O(1). */
    @Synchronized
    fun record(
        key: String,
        candle: Candle,
    ) {
        val deque = bars.getOrPut(key) { ArrayDeque(capacity) }
        if (deque.size == capacity) deque.removeFirst()
        deque.addLast(candle)
        counts[key] = (counts[key] ?: 0L) + 1L
    }

    /** The index of the oldest bar still held under [key] (bars before it were evicted). */
    @Synchronized
    fun oldestIndex(key: String): Long = (counts[key] ?: 0L) - (bars[key]?.size ?: 0)

    /** The [index]-th bar ever recorded under [key] (0-based), or null once it was evicted or before it closed. */
    @Synchronized
    fun at(
        key: String,
        index: Long,
    ): Candle? {
        val deque = bars[key] ?: return null
        val offset = index - ((counts[key] ?: 0L) - deque.size)
        return if (offset in 0 until deque.size) deque[offset.toInt()] else null
    }

    /** Pre-loads warmup bars (oldest-first); they count toward [countFor]. */
    fun seed(
        symbol: String,
        warmupBars: List<Candle>,
    ) {
        warmupBars.forEach { record(symbol, it) }
    }

    /** Newest [count] bars for [symbol], oldest-first; fewer if fewer have been seen. */
    @Synchronized
    fun last(
        symbol: String,
        count: Int,
    ): List<Candle> {
        val deque = bars[symbol] ?: return emptyList()
        return deque.toList().takeLast(count)
    }

    /** Total closed bars ever recorded for [symbol] (monotonic, unaffected by eviction). */
    @Synchronized
    fun countFor(symbol: String): Long = counts[symbol] ?: 0L
}
