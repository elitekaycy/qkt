package com.qkt.instrument

/**
 * Metadata for futures contracts and continuous streams, joined from the `futures:` roots and the
 * per-root contract catalogs. Precomputed at construction so lookups on the hot path are one map
 * read. Answers only symbols of declared roots; everything else falls through to the next layer.
 */
class ContractCatalogRegistry(
    val roots: List<FuturesRoot>,
    catalogs: Map<String, ContractCatalog>,
) : InstrumentRegistry {
    private val table: Map<String, InstrumentMeta> =
        buildMap {
            fun add(
                symbol: String,
                meta: () -> InstrumentMeta,
            ) {
                QktSymbols.requireFileSafe(symbol)
                require(symbol !in this) { "futures symbol $symbol is claimed by more than one root" }
                put(symbol, meta())
            }
            for (root in roots) {
                for (selector in ContinuousSelector.entries) {
                    val symbol = selector.symbolFor(root.root)
                    add(symbol) { root.metaFor(symbol, expiryMs = null) }
                }
                for (contract in catalogs[root.root]?.sorted()?.contracts.orEmpty()) {
                    require(contract.symbol.startsWith(root.symbol) && contract.symbol.length > root.symbol.length) {
                        "contract ${contract.symbol} does not belong to root ${root.root}"
                    }
                    val symbol = "${root.venue}:${contract.symbol}"
                    add(symbol) { root.metaFor(symbol, contract.expiryMs) }
                }
            }
        }

    override fun lookup(qktSymbol: String): InstrumentMeta? = table[qktSymbol]

    override fun missingReason(qktSymbol: String): String? {
        if (qktSymbol in table) return null
        val venue = qktSymbol.substringBefore(':')
        val name = qktSymbol.substringAfter(':')
        val selectors = ContinuousSelector.entries.joinToString { "@${it.token}" }
        if (roots.any { it.venue == venue && '@' in name && name.substringBefore('@') == it.symbol }) {
            return "$qktSymbol: unknown continuous selector; use $selectors"
        }
        val root =
            roots.firstOrNull { it.venue == venue && name.startsWith(it.symbol) && name.length > it.symbol.length }
                ?: return null
        val catalog = "contracts/${root.venue}/${root.symbol}.json"
        return "$qktSymbol looks like a contract of ${root.root} but is not in $catalog; " +
            "refresh it with qkt fetch ${root.root} --catalog"
    }

    companion object {
        /** A registry for [roots] with each root's catalog read from [store] (absent catalogs are empty). */
        fun load(
            roots: List<FuturesRoot>,
            store: ContractCatalogStore,
        ): ContractCatalogRegistry =
            ContractCatalogRegistry(roots, roots.mapNotNull { r -> store.read(r.root)?.let { r.root to it } }.toMap())
    }
}
