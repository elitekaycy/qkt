package com.qkt.research

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.broker.Broker
import com.qkt.broker.CompositeBroker
import com.qkt.broker.MT5BrokerSimulator
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.marketdata.source.SymbolPattern

/**
 * The simulated broker a replay of [symbols] fills against: one [ExecutionSimulationConfig.brokerKind]
 * broker, or, when strategies declare broker-qualified streams, a [CompositeBroker] with one such
 * broker per route in [brokerSymbols] iteration order. Futures symbols always fill on the exchange
 * stack instead ([replayFuturesRoutes]), whatever the broker kind; a run without them gets exactly
 * the broker it got before futures existed. Built once per replay.
 */
internal fun replayBroker(
    executionConfig: ExecutionSimulationConfig,
    bus: EventBus,
    clock: FixedClock,
    books: ReplayBooks,
    barFills: Boolean,
    calendar: TradingCalendar,
    brokerSymbols: Map<String, Set<String>>,
    symbols: Collection<String>,
): Broker {
    val brokerFactory: () -> Broker = {
        when (executionConfig.brokerKind) {
            BrokerKind.PAPER ->
                PaperBroker(
                    bus,
                    clock,
                    books.priceTracker,
                    books.instruments,
                    fillAtTriggerPrice = barFills,
                    calendar = calendar,
                    positionMode = executionConfig.positionMode,
                )
            BrokerKind.MT5_SIM ->
                MT5BrokerSimulator(
                    bus,
                    clock,
                    books.priceTracker,
                    books.instruments,
                    slippage = executionConfig.slippageModel(),
                    latencyMs = executionConfig.latencyMs,
                    stopLatencyMs = executionConfig.stopLatencyMs,
                    takeProfitFill = executionConfig.takeProfitFill,
                    enforceStopsLevel = executionConfig.enforceStopsLevel,
                    rejectionModel = executionConfig.rejectionModel(),
                    partialFillModel = executionConfig.partialFillModel(),
                    positionMode = executionConfig.positionMode,
                    orderSpacingMs = executionConfig.orderSpacingMs,
                )
        }
    }
    val futures =
        replayFuturesRoutes(executionConfig, bus, clock, books, barFills, calendar, symbols) +
            replayOptionRoutes(bus, clock, books, calendar, symbols)
    if (futures.routes.isEmpty()) {
        return if (brokerSymbols.isEmpty()) {
            brokerFactory()
        } else {
            CompositeBroker(
                routesOf(brokerSymbols, brokerFactory),
                bus = bus,
            )
        }
    }
    // Option root feeds and chain analytics are read-only streams: they never need a broker.
    val readOnly = { s: String -> s.startsWith(OptionRootSymbol.PREFIX) || s.startsWith(ChainAnalyticsSymbol.PREFIX) }
    val others = symbols.filterNot { it in futures.symbols || readOnly(it) }
    return if (brokerSymbols.isEmpty()) {
        CompositeBroker(futures.routes, fallback = if (others.isEmpty()) null else brokerFactory(), bus = bus)
    } else {
        val remaining =
            brokerSymbols
                .mapValues { (_, syms) ->
                    syms.filterNot { it in futures.symbols || readOnly(it) }.toSet()
                }.filterValues { it.isNotEmpty() }
        CompositeBroker(futures.routes + routesOf(remaining, brokerFactory), bus = bus)
    }
}

private fun routesOf(
    brokerSymbols: Map<String, Set<String>>,
    brokerFactory: () -> Broker,
): List<Pair<SymbolPattern, Broker>> =
    brokerSymbols.map { (_, syms) ->
        SymbolPattern.exactSet(syms.toSet()) to
            brokerFactory()
    }
