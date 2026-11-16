package com.qkt.instrument

import java.nio.file.Path

/**
 * Metadata for the contracts of the `events:` families, joined with their event catalogs and precomputed so a lookup
 * is one map read: every catalogued contract is an [InstrumentMeta] carrying its [EventTerms]. Answers only contracts
 * of declared families; everything else falls through to the next layer.
 */
class EventCatalogRegistry(
    private val families: List<EventFamily>,
    private val catalogs: Map<String, EventCatalog>,
) : InstrumentRegistry,
    EventDirectory {
    private val table: Map<String, InstrumentMeta> =
        buildMap {
            for (family in families) {
                for (contract in catalogs[family.root]?.sorted()?.contracts.orEmpty()) {
                    val symbol = contract.qktSymbol(family.root)
                    require(symbol !in this) { "event symbol $symbol is claimed by more than one family" }
                    put(symbol, family.metaFor(symbol, contract.terms(family.root)))
                }
            }
        }

    private val byFamily: Map<String, List<String>> =
        families.associate { f -> f.root to table.keys.filter { termsOf(it).root == f.root } }

    /** A catalogued contract's metadata, or a declared family's slot's ([EventFamily.metaFor] without terms). */
    override fun lookup(qktSymbol: String): InstrumentMeta? =
        table[qktSymbol] ?: SlotSymbol.parse(qktSymbol)?.let { family(it.root)?.metaFor(qktSymbol, null) }

    override fun events(): EventDirectory = this

    override fun family(root: String): EventFamily? = families.firstOrNull { it.root == root }

    override fun contracts(root: String): List<String> = byFamily[root].orEmpty()

    override fun aliveAt(
        root: String,
        atMs: Long,
    ): List<String> =
        contracts(root).filter {
            val terms = termsOf(it)
            terms.openAtMs <= atMs && atMs < terms.expiryMs
        }

    override fun missingReason(qktSymbol: String): String? {
        if (qktSymbol in table) return null
        val family =
            families.firstOrNull { qktSymbol.startsWith("${it.root}_") } ?: return null
        return "$qktSymbol looks like a contract of ${family.root} but is not in " +
            "contracts/${family.venue}/${family.root.substringAfter(':')}.events.json; " +
            "import it with qkt data import <file> --as ${family.root} --kind events"
    }

    private fun termsOf(symbol: String): EventTerms = table.getValue(symbol).derivative as EventTerms

    companion object {
        /** The registry for [families], each family's catalog read from [dataRoot] (absent catalogs list nothing). */
        fun load(
            families: List<EventFamily>,
            dataRoot: Path,
        ): EventCatalogRegistry {
            val store = EventCatalogStore(dataRoot)
            val catalogs = families.mapNotNull { f -> store.read(f.root)?.let { f.root to it } }.toMap()
            return EventCatalogRegistry(families, catalogs)
        }
    }
}
