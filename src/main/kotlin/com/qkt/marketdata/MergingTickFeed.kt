package com.qkt.marketdata

import java.util.PriorityQueue

/**
 * K-way merge of multiple feeds in monotonic timestamp order.
 *
 * Used to combine per-symbol feeds into a single chronological stream for multi-asset
 * backtests. Tie-break: feeds earlier in [feeds] win — keeps output deterministic.
 */
class MergingTickFeed(
    private val feeds: List<TickFeed>,
) : TickFeed {
    private data class HeadEntry(
        val feedIndex: Int,
        val tick: Tick,
    )

    private val heap: PriorityQueue<HeadEntry> =
        PriorityQueue(compareBy({ it.tick.timestamp }, { it.feedIndex }))

    init {
        feeds.forEachIndexed { i, f ->
            f.next()?.let { heap.add(HeadEntry(i, it)) }
        }
    }

    override fun next(): Tick? {
        val entry = heap.poll() ?: return null
        feeds[entry.feedIndex].next()?.let { heap.add(HeadEntry(entry.feedIndex, it)) }
        return entry.tick
    }

    override fun close() {
        feeds.forEach { runCatching { it.close() } }
    }
}
