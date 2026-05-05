package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

fun interface SymbolPattern {
    fun matches(symbol: String): Boolean

    companion object {
        fun prefix(prefix: String): SymbolPattern = SymbolPattern { it.startsWith(prefix) }

        fun exact(symbol: String): SymbolPattern = SymbolPattern { it == symbol }
    }
}

class CompositeMarketSource(
    private val routes: List<Pair<SymbolPattern, MarketSource>>,
    private val fallback: MarketSource,
) : MarketSource {
    override val name: String = "Composite"

    override val capabilities: Set<MarketSourceCapability> =
        (routes.map { it.second.capabilities } + listOf(fallback.capabilities)).flatten().toSet()

    override fun supports(symbol: String): Boolean = sourceFor(symbol).supports(symbol)

    private fun sourceFor(symbol: String): MarketSource =
        routes.firstOrNull { (pat, _) -> pat.matches(symbol) }?.second ?: fallback

    override fun liveTicks(symbols: List<String>): TickFeed {
        val grouped = symbols.groupBy { sourceFor(it) }
        if (grouped.size == 1) {
            return grouped.keys.first().liveTicks(symbols)
        }
        throw UnsupportedDataException(
            MarketSourceCapability.LIVE_TICKS,
            "CompositeMarketSource cannot fan-in live feeds in Phase 7a; planned for Phase 7b",
        )
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = sourceFor(symbol).bars(symbol, window, range)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = sourceFor(symbol).ticks(symbol, range)
}
