package com.qkt.research

import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.broker.Broker
import com.qkt.broker.MT5BrokerSimulator
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.events.EventExpiry
import com.qkt.broker.events.EventVenue
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.events.TickEvent
import com.qkt.instrument.EventTerms
import com.qkt.marketdata.source.SymbolPattern

/**
 * Routes a replay's event contracts to one [EventVenue]: orders fill at the contracts' quoted bid and ask (a buy at
 * the ask, a sell at the bid, netting, at any hour) and expiries settle at the catalogued outcome ([EventExpiry]),
 * recorded in the books' settlement log. Every event contract is routed, so contracts picked after start are tradable.
 * Empty when the run declares no event contract, so other runs build exactly what they built before.
 * No liquidator is registered: an event position is fully collateralised (its premium at stake), so it can never fall
 * below maintenance margin.
 */
internal fun replayEventRoutes(
    executionConfig: ExecutionSimulationConfig,
    bus: EventBus,
    clock: FixedClock,
    books: ReplayBooks,
    barFills: com.qkt.backtest.BarFills,
    symbols: Collection<String>,
): ReplayExchangeRoutes {
    val instruments = books.instruments
    val isEvent = { s: String -> instruments.lookup(s)?.derivative is EventTerms }
    val events = symbols.filterTo(LinkedHashSet(), isEvent)
    if (events.isEmpty()) return ReplayExchangeRoutes(emptyList(), emptySet())
    lateinit var fills: Broker
    val expiry = EventExpiry(bus, instruments, books.strategyPositions, books.settlements) { fills.cancel(it) }
    // Settle before the venue sees the tick, so an expired contract's working orders never fill on it.
    bus.subscribe<TickEvent> { e -> expiry.onTime(e.tick.timestamp) }
    fills =
        MT5BrokerSimulator(
            bus,
            clock,
            books.priceTracker,
            instruments,
            slippage = executionConfig.slippageModel(),
            positionMode = PositionAccountingMode.NETTING,
            fillAtTriggerPrice = barFills::at,
        )
    val venue = EventVenue(fills, expiry, bus, clock, instruments)
    val pattern = SymbolPattern { s -> isEvent(s) }
    return ReplayExchangeRoutes(listOf<Pair<SymbolPattern, Broker>>(pattern to venue), events)
}
