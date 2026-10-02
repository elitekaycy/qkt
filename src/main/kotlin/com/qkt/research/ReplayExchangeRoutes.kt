package com.qkt.research

import com.qkt.broker.Broker
import com.qkt.marketdata.source.SymbolPattern

/** The replay routes for a run's exchange-traded (futures and option) [symbols], and those symbols. */
internal data class ReplayExchangeRoutes(
    val routes: List<Pair<SymbolPattern, Broker>>,
    val symbols: Set<String>,
) {
    /** Both sets of routes. */
    operator fun plus(other: ReplayExchangeRoutes) =
        ReplayExchangeRoutes(routes + other.routes, symbols + other.symbols)
}
