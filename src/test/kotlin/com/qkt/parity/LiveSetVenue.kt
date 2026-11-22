package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.ExpandableTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal

/**
 * A venue's live feed over [ticks]: only the symbols subscribed so far, running each of [polls] once its instant is
 * reached, on the feed's thread, before the tick that reached it. With a [history] tape it also serves bars built
 * from that tape, as a venue's bar history does: a caller asks for the bars that ended by some instant.
 */
internal class LiveSetVenue(
    private val ticks: List<Tick>,
    private val polls: Map<Long, () -> Unit>,
    private val history: List<Tick> = emptyList(),
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
    override val capabilities =
        setOfNotNull(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS.takeIf { history.isNotEmpty() })

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        if (history.isEmpty()) super.bars(symbol, window, range) else tapeBars(history, symbol, window)

    override fun supports(symbol: String) = true

    override fun liveTicks(symbols: List<String>): TickFeed = feed.also { it.add(symbols) }
}

/** The bars of [symbol] every [window] that [tape] builds, one per window that has a tick, oldest first. */
internal fun tapeBars(
    tape: List<Tick>,
    symbol: String,
    window: TimeWindow,
): Sequence<Candle> =
    tape
        .filter { it.symbol == symbol }
        .groupBy { it.timestamp - Math.floorMod(it.timestamp, window.durationMs) }
        .toSortedMap()
        .map { (start, ticks) ->
            val prices = ticks.map { it.price }
            Candle(
                symbol,
                prices.first(),
                prices.max(),
                prices.min(),
                prices.last(),
                BigDecimal(ticks.size),
                start,
                start + window.durationMs,
            )
        }.asSequence()
