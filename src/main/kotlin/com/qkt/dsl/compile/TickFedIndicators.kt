package com.qkt.dsl.compile

import com.qkt.marketdata.Tick

/** Feeds raw ticks to the indicators that consume ticks rather than candles (e.g. VWAP). */
internal class TickFedIndicators(
    private val streams: Map<String, HubKey>,
    private val bindings: IndicatorBinding.Bag,
) {
    // Symbol-keyed view of tick-fed indicator bindings, built on first tick — most strategies
    // have none, and iterating the streams map per tick to discover that was pure overhead.
    private val tickFedBySymbol: Map<String, List<IndicatorBinding>> by lazy {
        buildMap<String, MutableList<IndicatorBinding>> {
            for ((alias, key) in streams) {
                val tickBindings = bindings.tickFedForAlias(alias)
                if (tickBindings.isEmpty()) continue
                getOrPut(key.qktSymbol) { mutableListOf() }.addAll(tickBindings)
            }
        }
    }

    fun onTick(tick: Tick) {
        // Phase 25E: feed tick-fed indicators (e.g. VWAP) on every raw tick.
        // Candle-fed indicators keep updating only at candle close in [AliasCloseEvaluator.evaluate]; the
        // two paths are disjoint by indicator input kind, so there's no double-feeding.
        if (tickFedBySymbol.isEmpty()) return
        val tickBindings = tickFedBySymbol[tick.symbol] ?: return
        for (b in tickBindings) b.updateFromTick(tick)
    }
}
