package com.qkt.cli.daemon

import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.sets.DynamicSetMembership
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SetOwnership
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.persistence.StatePersistor
import org.slf4j.LoggerFactory

/**
 * What a restarting strategy still has at the venue, and which of its moving symbol sets each part belongs to.
 *
 * - [symbols]: every symbol with an open leg or a working order in the persisted state.
 * - [bySet]: for each moving set (by alias), the bare symbols among them the set itself had as members before the
 *   restart ([SetOwnership]) and the venue still lists unexpired. A position a plain stream or another set opened is
 *   never handed to a set, e.g. set `m` owned `[A]`; holding `GW:A` and `GW:FOO` gives `m -> [A]`.
 * - [settled]: owned symbols the venue no longer lists, or lists as expired. Nothing can trade them any more (the
 *   venue settles an expired contract itself), so they are left out instead of stopping the whole restart.
 */
internal class LiveSetHoldings(
    val symbols: Set<String>,
    val bySet: Map<String, Set<String>>,
    val settled: Set<String>,
) {
    companion object {
        private val log = LoggerFactory.getLogger(LiveSetHoldings::class.java)

        /** [ast]'s holdings in [persistor] as of [nowMs], split over its moving sets with [catalog]'s listings. */
        fun of(
            ast: StrategyAst,
            catalog: SetCatalog,
            persistor: StatePersistor,
            nowMs: Long,
        ): LiveSetHoldings {
            val held = heldSymbols(persistor, ast.name)
            val settled = sortedSetOf<String>()
            val bySet =
                ast.streams.filter { it.broker == SET_BROKER }.associate { decl ->
                    val spec = SymbolSetSpec.of(decl) as? SymbolSetSpec.Filtered
                    val owned = SetOwnership.load(persistor, ast.name, decl.alias).toSet()
                    val mine =
                        held
                            .filter { it.substringBefore(':').equals(spec?.venue, true) }
                            .filter { it.substringAfter(':') in owned }
                    val tradable =
                        if (spec == null || mine.isEmpty()) emptySet() else tradable(decl.alias, spec, catalog, nowMs)
                    settled += mine.filter { it.uppercase() !in tradable }
                    decl.alias to mine.filter { it.uppercase() in tradable }.mapTo(sortedSetOf()) { bare(it) }
                }
            if (settled.isNotEmpty()) {
                log.warn(
                    "strategy '{}' holds {}, which the venue no longer lists unexpired; left to the venue's settlement",
                    ast.name,
                    settled,
                )
            }
            return LiveSetHoldings(held, bySet, settled)
        }

        /** The qkt symbols (upper case) [catalog] lists for the set [alias] that have not expired at [nowMs]. */
        private fun tradable(
            alias: String,
            spec: SymbolSetSpec.Filtered,
            catalog: SetCatalog,
            nowMs: Long,
        ): Set<String> =
            catalog
                .venue(alias, spec)
                .rows
                .filterNot { DynamicSetMembership.expired(it, nowMs) }
                .mapTo(HashSet()) { it.qktSymbol.uppercase() }

        private fun bare(symbol: String): String = symbol.substringAfter(':')

        /** The symbols [strategyId]'s persisted state still has an open leg or a working order on. */
        private fun heldSymbols(
            persistor: StatePersistor,
            strategyId: String,
        ): Set<String> {
            val legs =
                persistor.legBookSymbols(strategyId).filter { symbol ->
                    persistor
                        .loadLegBook(strategyId, symbol)
                        ?.legs
                        .orEmpty()
                        .isNotEmpty()
                }
            return (legs + persistor.loadPendingOrders(strategyId).values.map { it.symbol }).toSortedSet()
        }
    }
}
