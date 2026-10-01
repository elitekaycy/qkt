package com.qkt.instrument

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Metadata for the option contracts of the `options:` roots, joined with their catalogs and
 * precomputed so a lookup is one map read. Answers only contracts of declared roots; everything else
 * falls through to the next layer.
 */
class OptionCatalogRegistry(
    private val roots: List<OptionRoot>,
    private val catalogs: Map<String, OptionCatalog>,
) : InstrumentRegistry,
    OptionDirectory {
    private val table: Map<String, InstrumentMeta> =
        buildMap {
            for (root in roots) {
                for (listing in catalogs[root.root]?.contracts.orEmpty()) {
                    require(listing.symbol.startsWith("${root.root.substringAfter(':')}-")) {
                        "option ${listing.symbol} in the catalog of ${root.root} does not belong to it"
                    }
                    val symbol = "${root.venue}:${OptionSymbols.qktCode(listing.symbol)}"
                    QktSymbols.requireFileSafe(symbol)
                    require(symbol !in this) { "option symbol $symbol is claimed by more than one root" }
                    put(symbol, root.metaFor(symbol, listing.toContract()))
                }
            }
        }

    override fun lookup(qktSymbol: String): InstrumentMeta? = table[qktSymbol]

    override fun options(): OptionDirectory = this

    override fun optionRoot(qktSymbol: String): OptionRoot? = if (qktSymbol in table) rootOf(qktSymbol) else null

    override fun venueName(qktSymbol: String): String? =
        optionRoot(
            qktSymbol,
        )?.let { OptionSymbols.venueName(qktSymbol.substringAfter(':'), it.root.substringAfter(':')) }

    override fun missingReason(qktSymbol: String): String? {
        if (qktSymbol in table) return null
        val root = rootOf(qktSymbol) ?: return null
        val name = root.root
        return "option $qktSymbol is not in the catalog of $name; refresh it with qkt fetch $name --catalog"
    }

    override fun deliveryPrice(qktSymbol: String): BigDecimal? {
        val terms = table[qktSymbol]?.derivative as? OptionTerms ?: return null
        val date =
            Instant
                .ofEpochMilli(terms.expiryMs)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString()
        return catalogs[terms.root]?.deliveryPrices?.get(date)?.let(::BigDecimal)
    }

    /** The declared root [qktSymbol] is an option code of: `DERIBIT:BTC_USDC_…_C` of `DERIBIT:BTC_USDC`. */
    private fun rootOf(qktSymbol: String): OptionRoot? =
        roots.firstOrNull {
            qktSymbol.startsWith("${it.venue}:") &&
                OptionSymbols.venueName(qktSymbol.substringAfter(':'), it.root.substringAfter(':')) != null
        }

    companion object {
        /** The registry for [roots], reading each root's catalog from [store] (absent catalogs list nothing). */
        fun load(
            roots: List<OptionRoot>,
            store: OptionCatalogStore,
        ): OptionCatalogRegistry =
            OptionCatalogRegistry(
                roots,
                roots
                    .mapNotNull { r ->
                        store.read(r.root)?.let {
                            r.root to
                                it
                        }
                    }.toMap(),
            )
    }
}
