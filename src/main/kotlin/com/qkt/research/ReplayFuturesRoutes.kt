package com.qkt.research

import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.broker.Broker
import com.qkt.broker.continuous.ContinuousContractBroker
import com.qkt.broker.continuous.ContractVenue
import com.qkt.broker.exchange.ExchangeSimulator
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.events.TickEvent
import com.qkt.instrument.FutureTerms
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.pnl.ContractFeeCommission
import com.qkt.pnl.NoCommission
import org.slf4j.LoggerFactory

/** The replay routes for a run's futures [symbols], and those symbols. */
internal data class ReplayFuturesRoutes(
    val routes: List<Pair<SymbolPattern, Broker>>,
    val symbols: Set<String>,
)

private val log = LoggerFactory.getLogger(ReplayFuturesRoutes::class.java)

/**
 * Routes a replay's futures symbols to the exchange stack: continuous streams to one
 * [ContinuousContractBroker] (each stream on its own [ExchangeSimulator], rolls recorded in the
 * books' ledger) and listed contracts to an [ExchangeSimulator] that matches on the engine's ticks.
 * Every exchange uses the run's slippage model and charges each root's fees on its fills. Empty
 * when the run trades no futures.
 */
internal fun replayFuturesRoutes(
    executionConfig: ExecutionSimulationConfig,
    bus: EventBus,
    clock: FixedClock,
    books: ReplayBooks,
    barFills: Boolean,
    calendar: TradingCalendar,
    symbols: Collection<String>,
): ReplayFuturesRoutes {
    val instruments = books.instruments
    val directory = instruments.futures() ?: return ReplayFuturesRoutes(emptyList(), emptySet())
    val continuous = symbols.filter { directory.rootOfContinuous(it) != null }.toSet()
    val listed = symbols.filter { (instruments.lookup(it)?.derivative as? FutureTerms)?.expiryMs != null }.toSet()
    if (continuous.isEmpty() && listed.isEmpty()) return ReplayFuturesRoutes(emptyList(), emptySet())
    warnIgnoredSimulation(executionConfig)
    val fees = ContractFeeCommission(instruments, NoCommission)

    fun exchange(
        venueBus: EventBus,
        prices: MarketPriceProvider,
    ) = ExchangeSimulator(
        venueBus,
        clock,
        prices,
        instruments,
        executionConfig.slippageModel(),
        fees,
        barFills,
        calendar,
        books.settlements,
    )
    val routes =
        buildList<Pair<SymbolPattern, Broker>> {
            if (continuous.isNotEmpty()) {
                val broker =
                    ContinuousContractBroker(
                        bus,
                        clock,
                        ContinuousChains(directory),
                        continuous,
                        books.rolls,
                        books.contractFills,
                    ) { venueBus, prices ->
                        exchange(venueBus, prices).let { ContractVenue(it, it::onTick) }
                    }
                add(SymbolPattern.exactSet(continuous) to broker)
            }
            if (listed.isNotEmpty()) {
                val simulator = exchange(bus, books.priceTracker)
                bus.subscribe<TickEvent> { e -> simulator.onTick(e.tick) }
                add(SymbolPattern.exactSet(listed) to simulator)
            }
        }
    return ReplayFuturesRoutes(routes, continuous + listed)
}

/** The exchange simulator models no latency, venue rejections or partial fills; say so when asked for them. */
private fun warnIgnoredSimulation(config: ExecutionSimulationConfig) {
    if (config.latencyMs > 0 ||
        config.stopLatencyMs > 0 ||
        config.rejectEvery != null ||
        config.partialFillFraction != null
    ) {
        log.warn("futures fill on the exchange simulator, which ignores latency, rejection and partial-fill settings")
    }
}
