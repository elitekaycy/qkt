package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.OptionMarks
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.marks.MarkPrices

class CompositeMarketSource(
    private val routes: List<Pair<SymbolPattern, MarketSource>>,
    private val fallback: MarketSource,
) : MarketSource,
    RefreshableBars {
    override val name: String = "Composite"

    override val capabilities: Set<MarketSourceCapability> =
        (routes.map { it.second.capabilities } + listOf(fallback.capabilities)).flatten().toSet()

    override fun supports(symbol: String): Boolean = sourceFor(symbol).supports(symbol)

    // Per-symbol capabilities come from the leaf that actually serves the symbol, not the union —
    // so e.g. a volume check on an MT5-routed symbol isn't masked by a crypto leaf that has volume.
    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> =
        sourceFor(symbol).capabilitiesFor(symbol)

    override fun marksFor(symbol: String): MarkPrices? = sourceFor(symbol).marksFor(symbol)

    override fun optionMarksFor(symbol: String): OptionMarks? = sourceFor(symbol).optionMarksFor(symbol)

    override fun tradeFlowFor(symbol: String): TradeFlow? = sourceFor(symbol).tradeFlowFor(symbol)

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
        val perVendor =
            grouped.map { (vendor, syms) ->
                VendorTickFeed(vendor.name, syms, vendor.liveTicks(syms))
            }
        return FanInTickFeed(perVendor)
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = sourceFor(symbol).bars(symbol, window, range)

    override fun canRefresh(symbol: String): Boolean =
        (sourceFor(symbol) as? RefreshableBars)?.canRefresh(symbol) ?: false

    override fun forgetBars(
        symbol: String,
        window: TimeWindow,
    ) {
        (sourceFor(symbol) as? RefreshableBars)?.forgetBars(symbol, window)
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = sourceFor(symbol).ticks(symbol, range)
}
