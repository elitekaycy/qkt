package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

class SequenceTickFeed(
    seq: Sequence<Tick>,
) : TickFeed {
    private val iter = seq.iterator()

    override fun next(): Tick? = if (iter.hasNext()) iter.next() else null

    override fun close() {}
}
