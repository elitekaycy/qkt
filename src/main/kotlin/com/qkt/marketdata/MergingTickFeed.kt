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
    // One reusable entry per feed: a merge advances one head per emitted tick, so allocating
    // a fresh entry (plus the boxed compareBy selectors) per tick dominated the merge's cost.
    // Mutating [tick] is safe because an entry is only updated while polled out of the heap.
    private class HeadEntry(
        val feedIndex: Int,
        var tick: Tick,
    )

    private val heap: PriorityQueue<HeadEntry> =
        PriorityQueue { a, b ->
            val byTs = a.tick.timestamp.compareTo(b.tick.timestamp)
            if (byTs != 0) byTs else a.feedIndex - b.feedIndex
        }

    init {
        feeds.forEachIndexed { i, f ->
            f.next()?.let { heap.add(HeadEntry(i, it)) }
        }
    }

    override fun next(): Tick? {
        val entry = heap.poll() ?: return null
        val tick = entry.tick
        feeds[entry.feedIndex].next()?.let { head ->
            entry.tick = head
            heap.add(entry)
        }
        return tick
    }

    override fun close() {
        feeds.forEach { runCatching { it.close() } }
    }
}
