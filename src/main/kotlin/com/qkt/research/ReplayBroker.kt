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
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.SymbolPattern

/**
 * The simulated broker a replay fills against: one [ExecutionSimulationConfig.brokerKind] broker, or,
 * when strategies declare broker-qualified streams, a [CompositeBroker] with one such broker per
 * route in [brokerSymbols] iteration order. Built once per replay.
 */
internal fun replayBroker(
    executionConfig: ExecutionSimulationConfig,
    bus: EventBus,
    clock: FixedClock,
    priceTracker: MarketPriceTracker,
    instruments: InstrumentRegistry,
    barFills: Boolean,
    calendar: TradingCalendar,
    brokerSymbols: Map<String, Set<String>>,
): Broker {
    val brokerFactory: () -> Broker = {
        when (executionConfig.brokerKind) {
            BrokerKind.PAPER ->
                PaperBroker(
                    bus,
                    clock,
                    priceTracker,
                    instruments,
                    fillAtTriggerPrice = barFills,
                    calendar = calendar,
                    positionMode = executionConfig.positionMode,
                )
            BrokerKind.MT5_SIM ->
                MT5BrokerSimulator(
                    bus,
                    clock,
                    priceTracker,
                    instruments,
                    slippage = executionConfig.slippageModel(),
                    latencyMs = executionConfig.latencyMs,
                    stopLatencyMs = executionConfig.stopLatencyMs,
                    takeProfitFill = executionConfig.takeProfitFill,
                    enforceStopsLevel = executionConfig.enforceStopsLevel,
                    rejectionModel = executionConfig.rejectionModel(),
                    partialFillModel = executionConfig.partialFillModel(),
                    positionMode = executionConfig.positionMode,
                )
        }
    }
    return if (brokerSymbols.isEmpty()) {
        brokerFactory()
    } else {
        CompositeBroker(
            routes =
                brokerSymbols.map { (_, syms) ->
                    SymbolPattern
                        .exactSet(syms.toSet()) to brokerFactory()
                },
            bus = bus,
        )
    }
}
