package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.derivatives.events.EventChainSymbol
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.SlotSymbol
import com.qkt.marketdata.source.LiveSlotMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.strategy.Strategy

/**
 * A live session's [source] serving the event slots and event `CHAIN:` streams its [strategies] declare
 * ([LiveSlotMarketSource]), over the families [instruments] lists; [source] itself when they declare none. A slot
 * declared on several windows re-picks on the shortest of them.
 */
internal fun liveSlotSource(
    source: MarketSource,
    strategies: List<Pair<String, Strategy>>,
    instruments: InstrumentRegistry?,
): MarketSource {
    val streams =
        strategies
            .mapNotNull { it.second as? DslCompiledStrategy }
            .flatMap { it.declaredStreams.values }
            .filter { SlotSymbol.isSlot(it.qktSymbol) || EventChainSymbol.isEventChain(it.qktSymbol) }
    if (streams.isEmpty()) return source
    val registry =
        requireNotNull(instruments) {
            "event streams ${streams.map { it.qktSymbol }.distinct()} need the session's instrument registry"
        }
    val windows =
        streams
            .groupBy { it.qktSymbol }
            .mapValues { (_, keys) -> keys.minOf { TimeWindow.parse(it.timeframe).durationMs } }
    return LiveSlotMarketSource(source, registry, windows)
}
