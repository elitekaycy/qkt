package com.qkt.marketdata.store.deribit

import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.ChainSnapshot
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One live snapshot of a root's option chain from Deribit's book summary: every catalogued contract's
 * best bid and ask (a missing side stays absent), mark, mark IV, rate, and its expiry's forward as the
 * underlying; a contract still listed at or after its expiry is left out. The venue stamps rows
 * separately (tens of milliseconds apart); the snapshot is stamped at the newest row and each quote carries its row's age, so nothing in it postdates its instant.
 */
class DeribitBookSnapshot(
    private val client: DeribitClient,
) {
    /** The snapshot and the root's listed contracts the catalog does not hold yet. */
    data class Taken(
        val snapshot: ChainSnapshot,
        val unknownContracts: Set<String>,
    )

    /** Takes [root]'s chain now, quoting only contracts in [catalog]. */
    fun take(
        root: OptionRoot,
        catalog: OptionCatalog,
    ): Taken {
        val prefix = root.root.substringAfter(':') + "-"
        val expiries = catalog.contracts.associate { it.symbol to it.expiryMs }
        val (listed, unknown) =
            client.optionBook(root.currency).filter { it.name.startsWith(prefix) }.partition {
                it.name in
                    expiries
            }
        check(listed.isNotEmpty()) { "Deribit's book lists none of ${root.root}'s catalogued contracts" }
        val atMs = listed.maxOf { it.createdMs }
        val known = listed.filter { expiries.getValue(it.name) > atMs }
        check(known.isNotEmpty()) { "every catalogued ${root.root} contract on Deribit's book has expired" }
        val quotes = known.sortedBy { it.name }.map { it.toQuote(atMs) }
        return Taken(ChainSnapshot(root.root, atMs, quotes), unknown.mapTo(sortedSetOf()) { it.name })
    }

    private fun DeribitBookRow.toQuote(atMs: Long) =
        ChainQuote(
            atMs = atMs,
            contract = name,
            bid = bid.decimal(),
            ask = ask.decimal(),
            mark = requireNotNull(mark.decimal()) { "Deribit book row $name has no mark_price" },
            markIv = markIv.decimal(),
            underlying = requireNotNull(underlying.decimal()) { "Deribit book row $name has no underlying_price" },
            rate = rate.decimal(),
            markAgeMs = atMs - createdMs,
            source = QuoteSource.BOOK,
            index = index.decimal(),
        )

    private fun JsonPrimitive?.decimal(): BigDecimal? = this?.contentOrNull?.let(::BigDecimal)
}
