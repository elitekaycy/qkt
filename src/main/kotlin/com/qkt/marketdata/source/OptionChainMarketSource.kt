package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionQuotes
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.marketdata.Tick
import java.nio.file.Path
import java.time.ZoneOffset

/**
 * Market data for option contracts from their roots' stored chains: one tick per snapshot that quotes
 * the contract, priced at its mark, with the bid and ask [OptionQuotes] allows (absent sides stay
 * null, never zero, so a deep out-of-the-money quote is not dropped as malformed). A mark at or below
 * zero emits nothing; nothing is emitted at or after the contract's expiry.
 */
class OptionChainMarketSource(
    private val dataRoot: Path,
    private val instruments: InstrumentRegistry,
) : MarketSource {
    override val name: String = "option-chains"
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.TICKS)

    override fun supports(symbol: String): Boolean = instruments.options()?.optionRoot(symbol) != null

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val options = requireNotNull(instruments.options()) { "$symbol is not a catalogued option" }
        val root = requireNotNull(options.optionRoot(symbol)) { "$symbol is not a catalogued option" }
        val name = requireNotNull(options.venueName(symbol))
        val source =
            requireNotNull(root.chains) { "${root.root} declares no chain series to trade on (chains: trade | book)" }
        val terms = instruments.lookup(symbol)?.derivative as? OptionTerms
        val expiryMs = requireNotNull(terms) { "$symbol has no option terms in the instrument registry" }.expiryMs
        val store = ChainSnapshotStore(dataRoot, source)
        val fromMs = range.from.toEpochMilli()
        val toMs = minOf(range.to.toEpochMilli(), expiryMs)
        val firstDay = range.from.atZone(ZoneOffset.UTC).toLocalDate()
        val lastDay = range.to.atZone(ZoneOffset.UTC).toLocalDate()
        return generateSequence(firstDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastDay) }
            .flatMap { day -> store.readDay(root.root, day).asSequence() }
            .filter { it.atMs in fromMs until toMs }
            .mapNotNull { snapshot -> snapshot.quotes.firstOrNull { it.contract == name } }
            .filter { it.mark.signum() > 0 }
            .map { quote ->
                val sides = OptionQuotes.sides(quote, root)
                Tick(symbol, quote.mark, quote.atMs, bid = sides.bid, ask = sides.ask)
            }
    }
}
