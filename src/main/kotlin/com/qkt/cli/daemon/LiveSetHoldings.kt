package com.qkt.cli.daemon

import com.qkt.cli.BacktestContext
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.sets.DynamicSetMembership
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SetOwnership
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.InstrumentMeta
import com.qkt.persistence.StatePersistor
import org.slf4j.LoggerFactory

/**
 * What a restarting strategy still has at the venue, and which of its moving symbol sets each part belongs to.
 *
 * - [symbols]: every symbol with an open leg in the persisted state.
 * - [bySet]: for each moving set (by alias), the bare symbols the set itself had as members before the restart
 *   ([SetOwnership]), that the strategy still holds or has an order working on, and that the venue lists unexpired.
 *   A position a plain stream or another set opened is never handed to a set, e.g. set `m` owned `[A]`; holding
 *   `GW:A` and `GW:FOO` gives `m -> [A]`.
 * - [settled]: such symbols that are proven expired: the listing shows them expired, or the expiry the set saved
 *   for them has passed. Nothing can trade them any more (the venue settles an expired contract itself), so they
 *   are left out instead of stopping the whole restart.
 *
 * A symbol a set held that the listing does not show, with no proof it expired, refuses the start: the listing may
 * be cut short or filtered, and a live position nothing manages is worse than a start that has to be repeated.
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
            val working = persistor.loadPendingOrders(ast.name).values.map { it.symbol }
            val settled = sortedSetOf<String>()
            val missing = sortedSetOf<String>()
            val bySet =
                ast.streams.filter { it.broker == SET_BROKER }.associate { decl ->
                    val spec = SymbolSetSpec.of(decl) as? SymbolSetSpec.Filtered
                    val owned = SetOwnership.load(persistor, ast.name, decl.alias).toSet()
                    val mine =
                        (held + working)
                            .filter { it.substringBefore(':').equals(spec?.venue, true) }
                            .filter { bare(it) in owned }
                    val rows = if (spec == null || mine.isEmpty()) emptyMap() else listed(decl.alias, spec, catalog)
                    val expiries = SetOwnership.expiries(persistor, ast.name, decl.alias)
                    val expired = { s: String -> (expiries[bare(s)] ?: Long.MAX_VALUE) <= nowMs }
                    val live = mine.filter { s -> rows[s.uppercase()]?.let { !gone(it, nowMs) } ?: false }
                    val over = (mine - live.toSet()).filter { rows[it.uppercase()] != null || expired(it) }
                    settled += over
                    missing += mine - live.toSet() - over.toSet()
                    decl.alias to live.mapTo(sortedSetOf()) { bare(it) }
                }
            if (missing.isNotEmpty()) {
                throw BacktestContext.Companion.SetupError(
                    "strategy '${ast.name}' holds ${missing.joinToString()} in a symbol set, but the venue's " +
                        "listing does not show it and nothing proves it expired, so no rule would manage that " +
                        "position; check the listing (or close the position) and start again",
                )
            }
            if (settled.isNotEmpty()) {
                log.warn("strategy '{}' holds {}, which expired; left to the venue's settlement", ast.name, settled)
            }
            return LiveSetHoldings(held, bySet, settled)
        }

        /** The rows [catalog] lists for the set [alias], by qkt symbol in upper case. */
        private fun listed(
            alias: String,
            spec: SymbolSetSpec.Filtered,
            catalog: SetCatalog,
        ): Map<String, InstrumentMeta> = catalog.venue(alias, spec).rows.associateBy { it.qktSymbol.uppercase() }

        private fun gone(
            row: InstrumentMeta,
            nowMs: Long,
        ): Boolean = DynamicSetMembership.expired(row, nowMs)

        private fun bare(symbol: String): String = symbol.substringAfter(':')

        /** The symbols [strategyId]'s persisted state still has an open leg on. */
        private fun heldSymbols(
            persistor: StatePersistor,
            strategyId: String,
        ): Set<String> =
            persistor.legBookSymbols(strategyId).filterTo(sortedSetOf()) { symbol ->
                persistor
                    .loadLegBook(strategyId, symbol)
                    ?.legs
                    .orEmpty()
                    .isNotEmpty()
            }
    }
}
