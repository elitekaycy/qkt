package com.qkt.marketdata.source

import com.qkt.derivatives.events.EventChainMetrics
import com.qkt.derivatives.events.EventChainSymbol
import com.qkt.derivatives.events.EventQuote
import com.qkt.instrument.FamilyContract
import com.qkt.instrument.OpenEvents
import com.qkt.marketdata.Tick

/**
 * One event analytics stream ([EventChainSymbol]) valued live, tick by tick: after each quote of a contract of the
 * family, the metric is valued over every contract of the selected event with each contract's latest quote, as
 * [EventChainMarketSource] values it from stored quotes. An instant where it is undefined has no tick. The family's
 * [contracts] are read as they are then (a live family lists new contracts while it runs).
 */
internal class EventChainFollower(
    private val symbol: String,
    private val contracts: () -> List<FamilyContract>,
) {
    private val stream = EventChainSymbol.parse(symbol)
    private val metric = requireNotNull(EventChainMetrics.of(stream.metric))
    private val book = HashMap<String, Tick>()
    private var listed: List<FamilyContract>? = null
    private var members: Set<String> = emptySet()
    private var open = OpenEvents(emptyList())

    /** Takes [tick] (of the family or not); returns the stream's value after it, or null when it has none. */
    fun step(tick: Tick): Tick? {
        val now = contracts()
        if (now !== listed) {
            listed = now
            members = now.mapTo(HashSet()) { it.symbol }
            open = OpenEvents(now)
        }
        if (tick.symbol !in members) return null
        book[tick.symbol] = tick
        val event = open.at(tick.timestamp).getOrNull(stream.eventOffset) ?: return null
        val quotes = event.contracts.map { c -> book[c.symbol].let { EventQuote(c.terms, it?.bid, it?.ask) } }
        return metric.value(quotes)?.let { Tick(symbol, it, tick.timestamp) }
    }
}
