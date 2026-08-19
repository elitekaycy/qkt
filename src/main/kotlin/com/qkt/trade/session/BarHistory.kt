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

    /** Appends one closed candle, evicting the oldest past [capacity]. O(1). */
    fun record(candle: Candle) {
        val deque = bars.getOrPut(candle.symbol) { ArrayDeque(capacity) }
        if (deque.size == capacity) deque.removeFirst()
        deque.addLast(candle)
        counts[candle.symbol] = (counts[candle.symbol] ?: 0L) + 1L
    }

    /** Pre-loads warmup bars (oldest-first); they count toward [countFor]. */
    fun seed(
        symbol: String,
        warmupBars: List<Candle>,
    ) {
        warmupBars.forEach { record(it) }
    }

    /** Newest [count] bars for [symbol], oldest-first; fewer if fewer have been seen. */
    fun last(
        symbol: String,
        count: Int,
    ): List<Candle> {
        val deque = bars[symbol] ?: return emptyList()
        return deque.toList().takeLast(count)
    }

    /** Total closed bars ever recorded for [symbol] (monotonic, unaffected by eviction). */
    fun countFor(symbol: String): Long = counts[symbol] ?: 0L
}
