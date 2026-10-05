package com.qkt.dsl.compile

import com.qkt.marketdata.flow.FlowKind

/** One trade-flow series a strategy reads: [symbol]'s [kind]. */
data class FlowRead(
    val symbol: String,
    val kind: FlowKind,
)

/**
 * A compiled strategy that reads trade flow (`<alias>.buy_volume[1]`, ...). The runtime verifies its data source
 * serves each read ([com.qkt.marketdata.source.MarketSource.tradeFlowFor]) before it goes live.
 */
interface TradeFlowReader {
    /** The series the strategy's flow fields read. */
    val flowReads: Set<FlowRead>
}
