package com.qkt.research

import com.qkt.broker.Broker
import com.qkt.broker.options.OptionExchange
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.derivatives.options.chain.ChainQuoteLookup
import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.events.TickEvent
import com.qkt.instrument.optionSymbols
import com.qkt.marketdata.source.SymbolPattern

/**
 * Routes a replay's option contracts to one [OptionExchange] that fills on the stored chain of the
 * option roots' data root and settles expiries into the books' settlement log. The declared contracts
 * are routed, and every contract of a fed root (`OPTIONS:<VENUE>.<ROOT>`). Empty when the run neither
 * trades options nor feeds a root, so other runs build exactly what they built before.
 */
internal fun replayOptionRoutes(
    bus: EventBus,
    clock: FixedClock,
    books: ReplayBooks,
    calendar: TradingCalendar,
    symbols: Collection<String>,
): ReplayExchangeRoutes {
    val instruments = books.instruments
    val options = instruments.optionSymbols(symbols)
    val fedRoots = symbols.mapNotNull { OptionRootSymbol.parse(it).getOrNull() }
    if (options.isEmpty() && fedRoots.isEmpty()) return ReplayExchangeRoutes(emptyList(), emptySet())
    val dataRoot = requireNotNull(instruments.options()?.dataRoot) { "option symbols have no chain data root" }
    val exchange =
        OptionExchange(bus, clock, instruments, ChainQuoteLookup(dataRoot, instruments), calendar, books.settlements)
    bus.subscribe<TickEvent> { e -> exchange.onTick(e.tick) }
    // A fed root routes every contract of the root, including ones a structure picks at fire time.
    val pattern = SymbolPattern { s -> s in options || fedRoots.any { it.covers(s) } }
    return ReplayExchangeRoutes(listOf<Pair<SymbolPattern, Broker>>(pattern to exchange), options)
}
