package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataLifecycleFeed

/**
 * A live feed with the ticks [steps] derive from it: each tick of [inner] passes through, then whatever each step
 * makes of it (an event slot's copy, an event `CHAIN:` value). Disconnects and reconnects are [inner]'s.
 */
internal open class FamilyTickFeed(
    private val inner: TickFeed,
    private val steps: List<(Tick) -> Tick?>,
) : TickFeed {
    private val pending = ArrayDeque<Tick>()

    override fun next(): Tick? {
        pending.removeFirstOrNull()?.let { return it }
        val tick = inner.next() ?: return null
        for (step in steps) step(tick)?.let(pending::addLast)
        return tick
    }

    override fun close() = inner.close()

    private class Lifecycle(
        inner: TickFeed,
        lifecycle: MarketDataLifecycleFeed,
        steps: List<(Tick) -> Tick?>,
    ) : FamilyTickFeed(inner, steps),
        MarketDataLifecycleFeed by lifecycle

    companion object {
        /** [inner] with [steps], keeping its lifecycle when it is a live one. */
        fun over(
            inner: TickFeed,
            steps: List<(Tick) -> Tick?>,
        ): TickFeed =
            if (inner is MarketDataLifecycleFeed) Lifecycle(inner, inner, steps) else FamilyTickFeed(inner, steps)
    }
}
