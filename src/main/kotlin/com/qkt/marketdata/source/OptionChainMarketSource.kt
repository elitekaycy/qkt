package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.derivatives.options.OptionPayoff
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionMarks
import com.qkt.derivatives.options.chain.StoredOptionMarks
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.marketdata.Tick
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

/**
 * Market data for option contracts from their roots' stored chains: one tick per snapshot that quotes
 * the contract, priced at its mark, with the bid and ask [OptionQuotes] allows (absent sides stay
 * null, never zero, so a deep out-of-the-money quote is not dropped as malformed). A mark at or below
 * zero emits nothing. Quotes stop at the contract's expiry; when the window covers it, one
 * settlement print at the intrinsic value from the catalog's delivery price (zero out of the money,
 * no bid or ask) ends the stream, so the venue settles and positions mark at what expiry paid, like
 * the futures settlement print.
 */
class OptionChainMarketSource(
    private val dataRoot: Path,
    private val instruments: InstrumentRegistry,
) : MarketSource {
    override val name: String = "option-chains"
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.TICKS)

    override fun supports(symbol: String): Boolean = instruments.options()?.optionRoot(symbol) != null

    private val marks by lazy { StoredOptionMarks(instruments) }

    /** The contracts' quotes in their roots' stored chains, newest snapshot first ([StoredOptionMarks]). */
    override fun optionMarksFor(symbol: String): OptionMarks = marks

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val options = requireNotNull(instruments.options()) { "$symbol is not a catalogued option" }
        val root = requireNotNull(options.optionRoot(symbol)) { "$symbol is not a catalogued option" }
        val name = requireNotNull(options.venueName(symbol))
        val source =
            requireNotNull(root.chains) { "${root.root} declares no chain series to trade on (chains: trade | book)" }
        val terms =
            requireNotNull(instruments.lookup(symbol)?.derivative as? OptionTerms) { "$symbol has no option terms" }
        val expiryMs = terms.expiryMs
        val store = ChainSnapshotStore(dataRoot, source)
        val fromMs = range.from.toEpochMilli()
        val toMs = minOf(range.to.toEpochMilli(), expiryMs)
        val firstDay = range.from.atZone(ZoneOffset.UTC).toLocalDate()
        val lastDay = Instant.ofEpochMilli(toMs).atZone(ZoneOffset.UTC).toLocalDate()
        val quotes =
            generateSequence(firstDay) { it.plusDays(1) }
                .takeWhile { !it.isAfter(lastDay) }
                .flatMap { day -> store.readDay(root.root, day).asSequence() }
                .filter { it.atMs in fromMs until toMs }
                .mapNotNull { snapshot -> snapshot.quotes.firstOrNull { it.contract == name } }
                .filter { it.mark.signum() > 0 }
                .map { quote -> optionQuoteTick(symbol, quote, root) }
        if (expiryMs !in fromMs until range.to.toEpochMilli()) return quotes
        val day = Instant.ofEpochMilli(expiryMs).atZone(ZoneOffset.UTC).toLocalDate()
        val delivery =
            options.deliveryPrice(symbol)
                ?: error(
                    "$symbol expires in the run with no delivery price for $day; refresh it with qkt fetch ${root.root} --catalog",
                )
        return quotes + Tick(symbol, OptionPayoff.intrinsic(terms.right, terms.strike, delivery), expiryMs)
    }
}
