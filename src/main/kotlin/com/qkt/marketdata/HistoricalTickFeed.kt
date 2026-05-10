package com.qkt.marketdata

import java.nio.file.Path

/** In-memory tick feed — the canonical backtest source. Use [fromCsv] to load from disk. */
class HistoricalTickFeed(
    private val ticks: List<Tick>,
) : TickFeed {
    private var index = 0

    override fun next(): Tick? = if (index < ticks.size) ticks[index++] else null

    companion object {
        /** Reads a CSV file at [path] (gzipped accepted) and materializes it as a feed. */
        fun fromCsv(path: Path): HistoricalTickFeed {
            val all = mutableListOf<Tick>()
            CsvTickFeed(path).use { feed ->
                while (true) {
                    val t = feed.next() ?: break
                    all.add(t)
                }
            }
            return HistoricalTickFeed(all)
        }
    }
}
