package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.instrument.FamilyContract
import com.qkt.marketdata.Tick
import java.time.Instant
import java.util.PriorityQueue

/**
 * Every stored quote tick of an event family's contracts over a window, merged in time order (ties by catalog order)
 * and stamped with each contract's own symbol. A contract contributes only while alive (`openAtMs` to `expiryMs`), and
 * its data is opened only once the merge reaches its listing, so a long catalog costs one open read per live contract.
 */
internal object FamilyTicks {
    private class Head(
        val tick: Tick,
        val rest: Iterator<Tick>,
        val order: Int,
    )

    fun merged(
        inner: MarketSource,
        contracts: List<FamilyContract>,
        range: TimeRange,
    ): Sequence<Tick> {
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        val lives =
            contracts
                .mapIndexed { order, c ->
                    Triple(order, c, maxOf(fromMs, c.terms.openAtMs) until minOf(toMs, c.terms.expiryMs))
                }.filterNot { it.third.isEmpty() }
                .sortedWith(compareBy({ it.third.first }, { it.first }))
        return sequence {
            val heads = PriorityQueue<Head>(compareBy({ it.tick.timestamp }, { it.order }))
            var next = 0
            val due = {
                next < lives.size && (heads.isEmpty() || lives[next].third.first <= heads.peek().tick.timestamp)
            }
            while (true) {
                while (due()) {
                    val (order, contract, life) = lives[next++]
                    val window = TimeRange(Instant.ofEpochMilli(life.first), Instant.ofEpochMilli(life.last + 1))
                    val ticks = inner.ticks(contract.symbol, window).filter { it.timestamp in life }.iterator()
                    if (ticks.hasNext()) heads.add(Head(ticks.next(), ticks, order))
                }
                val head = heads.poll() ?: break
                yield(head.tick)
                if (head.rest.hasNext()) heads.add(Head(head.rest.next(), head.rest, head.order))
            }
        }
    }
}
