package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.derivatives.events.EventChainMetrics
import com.qkt.derivatives.events.EventChainSymbol
import com.qkt.derivatives.events.EventQuote
import com.qkt.instrument.FamilyDirectory
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OpenEvents
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * Event analytics streams (`CHAIN:<VENUE>.<FAMILY>.<metric>.<front|next>`, [EventChainSymbol]) from the per-contract
 * quote ticks of [inner]: after each instant at which the family quotes, the metric is valued over every contract of
 * the selected event ([EventChainMetrics]) with each contract's latest quote, and an instant where it is undefined has
 * no tick. One flat candle per observation serves warmup. Fails at construction when a stream in [declared] names an
 * unknown metric or an undeclared family.
 */
class EventChainMarketSource(
    private val inner: MarketSource,
    instruments: InstrumentRegistry,
    declared: List<String>,
) : MarketSource {
    private val families = FamilyDirectory(instruments)

    init {
        for (symbol in declared.filter { EventChainSymbol.isEventChain(it) }) {
            val stream = EventChainSymbol.parse(symbol)
            require(families.isFamily(stream.root)) {
                "$symbol: ${stream.root} is not an event family declared under events: in instruments.yaml"
            }
        }
    }

    override val name: String = "event-chain"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

    override fun supports(symbol: String): Boolean = EventChainSymbol.isEventChain(symbol)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = observations(symbol, range).map { (at, value) -> Tick(symbol, value, at) }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        observations(symbol, range).map { (at, v) ->
            Candle(symbol, v, v, v, v, Money.ZERO, at, at + window.durationMs)
        }

    private fun observations(
        symbol: String,
        range: TimeRange,
    ): Sequence<Pair<Long, BigDecimal>> {
        val stream = EventChainSymbol.parse(symbol)
        val metric = requireNotNull(EventChainMetrics.of(stream.metric))
        val contracts = families.contracts(stream.root)
        return sequence {
            val open = OpenEvents(contracts)
            val book = HashMap<String, Tick>()
            val ticks = FamilyTicks.merged(inner, contracts, range).iterator()
            var next: Tick? = if (ticks.hasNext()) ticks.next() else null
            while (next != null) {
                val at = next.timestamp
                while (next != null && next.timestamp == at) {
                    book[next.symbol] = next
                    next = if (ticks.hasNext()) ticks.next() else null
                }
                val event = open.at(at).getOrNull(stream.eventOffset) ?: continue
                val quotes = event.contracts.map { c -> book[c.symbol].let { EventQuote(c.terms, it?.bid, it?.ask) } }
                metric.value(quotes)?.let { yield(at to it) }
            }
        }
    }
}
