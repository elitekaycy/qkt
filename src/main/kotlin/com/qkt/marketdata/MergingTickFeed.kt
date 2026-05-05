package com.qkt.marketdata

import java.util.PriorityQueue

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
