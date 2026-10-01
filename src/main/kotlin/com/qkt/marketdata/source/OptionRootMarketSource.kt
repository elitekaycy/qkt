package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.derivatives.options.OptionPayoff
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionSymbols
import com.qkt.instrument.OptionTerms
import com.qkt.marketdata.Tick
import java.time.ZoneOffset
import java.util.TreeMap

/**
 * A whole option root as one feed (`OPTIONS:<VENUE>.<ROOT>`). Each stored day of the root's chain
 * series is decoded once, and every snapshot becomes one tick per quoted contract, priced as the
 * single-contract source prices it. A contract's quotes stop at its expiry. When the window covers that
 * expiry, a contract quoted in the run gets one settlement print at its intrinsic value from the
 * catalog's delivery price, emitted before any snapshot of the same instant. A contract without a recorded delivery
 * price gets none: one nobody holds needs none, and a held one fails at its settlement naming the
 * catalog refresh.
 */
class OptionRootMarketSource(
    private val instruments: InstrumentRegistry,
) : MarketSource {
    override val name: String = "option-root"
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.TICKS)

    override fun supports(symbol: String): Boolean = symbol.startsWith(OptionRootSymbol.PREFIX)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val feed = OptionRootSymbol.parse(symbol).getOrThrow()
        val options = instruments.options()
        val root =
            options?.root(feed.root)
                ?: error("${feed.root} of $symbol is not declared under options: in instruments.yaml")
        val series = root.chains ?: error("${feed.root} of $symbol declares no chain series (chains: trade | book)")
        val store =
            ChainSnapshotStore(requireNotNull(options.dataRoot) { "${feed.root} has no chain data root" }, series)
        val listings = options.listings(root.root)
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        return sequence {
            val seen = HashSet<String>()
            val prints = TreeMap<Long, MutableList<String>>()

            suspend fun SequenceScope<Tick>.printUpTo(atMs: Long) {
                while (prints.isNotEmpty() && prints.firstKey() <= atMs) {
                    val (expiry, symbols) = prints.pollFirstEntry()
                    for (contract in symbols.sorted()) settlement(contract, expiry)?.let { yield(it) }
                }
            }
            val days = generateSequence(range.from.atZone(ZoneOffset.UTC).toLocalDate()) { it.plusDays(1) }
            for (day in days.takeWhile { !it.isAfter(range.to.atZone(ZoneOffset.UTC).toLocalDate()) }) {
                for (snapshot in store.readDay(root.root, day).filter { it.atMs in fromMs until toMs }) {
                    printUpTo(snapshot.atMs)
                    for (quote in snapshot.quotes) {
                        val listing = listings[quote.contract] ?: continue
                        if (snapshot.atMs >= listing.expiryMs || quote.mark.signum() <= 0) continue
                        val contract = "${root.venue}:${OptionSymbols.qktCode(quote.contract)}"
                        if (seen.add(contract) && listing.expiryMs in fromMs until toMs) {
                            prints.getOrPut(listing.expiryMs) { mutableListOf() } += contract
                        }
                        yield(optionQuoteTick(contract, quote, root))
                    }
                }
            }
            printUpTo(toMs - 1)
        }
    }

    /** [contract]'s settlement print, or null when the catalog has no delivery price for its expiry. */
    private fun settlement(
        contract: String,
        expiryMs: Long,
    ): Tick? {
        val options = requireNotNull(instruments.options())
        val terms =
            requireNotNull(instruments.lookup(contract)?.derivative as? OptionTerms) { "$contract has no option terms" }
        val delivery = options.deliveryPrice(contract) ?: return null
        return Tick(contract, OptionPayoff.intrinsic(terms.right, terms.strike, delivery), expiryMs)
    }
}
