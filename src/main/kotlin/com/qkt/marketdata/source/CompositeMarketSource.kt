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

        fun exactSet(symbols: Set<String>): SymbolPattern = SymbolPattern { it in symbols }
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

    // Per-symbol capabilities come from the leaf that actually serves the symbol, not the union —
    // so e.g. a volume check on an MT5-routed symbol isn't masked by a crypto leaf that has volume.
    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> =
        sourceFor(symbol).capabilitiesFor(symbol)

    private fun sourceFor(symbol: String): MarketSource =
        routes.firstOrNull { (pat, _) -> pat.matches(symbol) }?.second ?: fallback

    override fun liveTicks(symbols: List<String>): TickFeed {
        if (symbols.isEmpty()) {
            throw UnsupportedDataException(
                MarketSourceCapability.LIVE_TICKS,
                "CompositeMarketSource: no symbols supplied",
            )
        }
        val grouped = symbols.groupBy { sourceFor(it) }
        if (grouped.size == 1) {
            return grouped.keys.first().liveTicks(symbols)
        }
        val perVendor: List<TickFeed> = grouped.map { (vendor, syms) -> vendor.liveTicks(syms) }
        return FanInTickFeed(perVendor)
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

private class FanInTickFeed(
    private val feeds: List<TickFeed>,
) : TickFeed {
    private val cursor: java.util.ArrayDeque<TickFeed> = java.util.ArrayDeque(feeds)

    override fun next(): Tick? {
        while (cursor.isNotEmpty()) {
            val first = cursor.peekFirst()
            val t = first.next()
            if (t != null) {
                cursor.removeFirst()
                cursor.addLast(first)
                return t
            }
            cursor.removeFirst()
        }
        return null
    }

    override fun close() {
        feeds.forEach { runCatching { it.close() } }
    }
}
