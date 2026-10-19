package com.qkt.research

import com.qkt.backtest.BarFills
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionFacts
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.backtest.SlippageSpec
import com.qkt.backtest.spreadModels
import com.qkt.broker.FillQuotes
import com.qkt.broker.SimulatedFillPrice
import com.qkt.instrument.InstrumentRegistry

/**
 * Records what a replay's fills rested on for its [ExecutionFacts] (#1437): the venue routing and
 * execution settings when the broker is built, and the quote tally of every mt5-sim broker it built.
 * Read once per result snapshot; the tally itself is counted by [SimulatedFillPrice] as it prices.
 */
internal class ReplayExecution {
    private var described: ExecutionFacts? = null
    private val prices = ArrayList<SimulatedFillPrice>()

    /** State the run's broker setup: [brokerSymbols] fill on the simulated broker, the rest on venues. */
    fun describe(
        config: ExecutionSimulationConfig,
        instruments: InstrumentRegistry,
        brokerSymbols: Set<String>,
        barFills: BarFills,
        futures: Set<String>,
        options: Set<String>,
    ) {
        val slipping = brokerSymbols + futures
        described =
            ExecutionFacts(
                broker = config.brokerKind,
                brokerSymbols = brokerSymbols,
                barLevelSymbols =
                    if (config.brokerKind ==
                        BrokerKind.PAPER
                    ) {
                        brokerSymbols.filter(barFills::at).toSet()
                    } else {
                        emptySet()
                    },
                spreadModels = spreadModels(instruments, brokerSymbols.toList(), config.brokerKind),
                slippage = config.slippage,
                slippagePoints = config.slippagePoints,
                instrumentSlippagePoints =
                    if (config.slippage == SlippageSpec.INSTRUMENT) {
                        slipping.associateWith { instruments.lookup(it)?.slippagePoints ?: 0 }
                    } else {
                        emptyMap()
                    },
                latencyMs = config.latencyMs,
                stopLatencyMs = config.stopLatencyMs,
                partialFillFraction = config.partialFillFraction,
                rejectEvery = config.rejectEvery,
                futures = futures,
                options = options,
            )
    }

    /** Count [price]'s quotes into the facts: one per mt5-sim broker the replay built. */
    fun track(price: SimulatedFillPrice) {
        prices += price
    }

    /** The facts so far, or null when the replay never described its broker. */
    fun snapshot(): ExecutionFacts? {
        val facts = described ?: return null
        if (facts.broker != BrokerKind.MT5_SIM) return facts
        val quotes = prices.fold(FillQuotes()) { acc, p -> acc + p.quotes() }
        val synthetic = prices.firstOrNull()?.syntheticSpreadPoints ?: facts.syntheticSpreadPoints
        return facts.copy(quotes = quotes, syntheticSpreadPoints = synthetic)
    }
}
