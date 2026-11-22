package com.qkt.parity

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.ExpandableTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability

/**
 * A venue's live feed over [ticks]: only the symbols subscribed so far, running each of [polls] once its instant is
 * reached, on the feed's thread, before the tick that reached it.
 */
internal class LiveSetVenue(
    private val ticks: List<Tick>,
    private val polls: Map<Long, () -> Unit>,
) : MarketSource {
    val feed =
        object : ExpandableTickFeed {
            private val served = HashSet<String>()
            private var next = 0
            private val due = polls.toSortedMap()

            @Synchronized
            override fun add(symbols: Collection<String>): Set<String> {
                served += symbols
                return symbols.toSet()
            }

            override fun next(): Tick? {
                while (next < ticks.size) {
                    val tick = ticks[next++]
                    while (due.isNotEmpty() && due.firstKey() <= tick.timestamp) {
                        due.remove(due.firstKey())?.invoke()
                    }
                    if (tick.symbol in served) return tick
                }
                return null
            }

            override fun drop(symbols: Collection<String>) = Unit

            override fun close() = Unit
        }

    override val name = "Venue"
    override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

    override fun supports(symbol: String) = true

    override fun liveTicks(symbols: List<String>): TickFeed = feed.also { it.add(symbols) }
}
