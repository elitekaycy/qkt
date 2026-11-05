package com.qkt.marketdata

import java.util.PriorityQueue

/**
 * K-way merge of multiple feeds in monotonic timestamp order.
 *
 * Used to combine per-symbol feeds into a single chronological stream for multi-asset
 * backtests. A tick that carries [Tick.receivedMs] (a golden capture's) is placed by that arrival time
 * instead, so ticks of several venues replay in the order live qkt processed them (#1625); each feed keeps
 * its own order. Tie-break: the earlier timestamp, then feeds earlier in [feeds] — keeps output deterministic.
 */
class MergingTickFeed(
    private val feeds: List<TickFeed>,
) : TickFeed {
    // One reusable entry per feed: a merge advances one head per emitted tick, so allocating
    // a fresh entry (plus the boxed compareBy selectors) per tick dominated the merge's cost.
    // Mutating [tick] is safe because an entry is only updated while polled out of the heap.
    private class HeadEntry(
        val feedIndex: Int,
        tick: Tick,
    ) {
        var tick: Tick = tick
            set(value) {
                field = value
                order = value.receivedMs ?: value.timestamp
            }

        // The merge position, unboxed once per head rather than on every comparison.
        var order: Long = tick.receivedMs ?: tick.timestamp
            private set
    }

    private val heap: PriorityQueue<HeadEntry> =
        PriorityQueue { a, b ->
            val byOrder = a.order.compareTo(b.order)
            val byTs = if (byOrder != 0) byOrder else a.tick.timestamp.compareTo(b.tick.timestamp)
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
