package com.qkt.marketdata

/**
 * Pull-based source of [Tick]s.
 *
 * Implementations: [HistoricalTickFeed] (in-memory), [CsvTickFeed] (CSV files),
 * [ConcatenatedTickFeed] (sequential merge), [MergingTickFeed] (k-way merge by time),
 * [RangeClippedTickFeed] (date-window filter), [MockTickFeed] (deterministic synthesizer).
 * Live vendors implement TickFeed too — the backtest and live runtime use the same shape.
 */
interface TickFeed : AutoCloseable {
    /** Returns the next tick, or `null` if the feed is exhausted. */
    fun next(): Tick?

    /** Releases underlying resources. Default no-op; live feeds override. */
    override fun close() {}
}
