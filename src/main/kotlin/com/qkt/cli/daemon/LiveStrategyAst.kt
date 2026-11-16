package com.qkt.cli.daemon

import com.qkt.cli.BacktestContext
import com.qkt.cli.SymbolSetResolution
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.persistence.StatePersistor
import java.nio.file.Path

/**
 * [file]'s strategy as a live session runs it: parsed, then its filtered symbol sets resolved once from [symbolSets]
 * (the venue listings) as of [nowMs], the way a backtest resolves them from its instruments file as of `--from`.
 * Without a catalog the sets stay, and the compiler refuses them by name. A start whose sets dropped a symbol the
 * strategy still holds in [persistor] is refused ([requireHeldMembersKept]).
 */
internal fun liveStrategyAst(
    file: Path,
    symbolSets: SetCatalog?,
    nowMs: Long,
    persistor: StatePersistor,
): StrategyAst {
    val ast =
        when (val parsed = Dsl.parseFile(file)) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Failure -> {
                val msg = parsed.errors.joinToString("\n") { e -> "${e.line}:${e.col} — ${e.message}" }
                error("parse failure for $file:\n$msg")
            }
        }
    val catalog = symbolSets ?: return ast
    val resolved = SymbolSetResolution.resolve(ast, catalog, nowMs, false)
    requireHeldMembersKept(ast, resolved, heldSymbols(persistor, ast.name))
    return resolved
}

/** The symbols [strategyId]'s persisted leg books still hold an open leg on. */
private fun heldSymbols(
    persistor: StatePersistor,
    strategyId: String,
): Set<String> =
    persistor
        .legBookSymbols(strategyId)
        .filterTo(sortedSetOf()) { persistor.loadLegBook(strategyId, it)?.legs.orEmpty().isNotEmpty() }

/**
 * Refuses a live start that would leave a held position with no rule: a symbol on a filtered set's venue that
 * [held] still has open, and that no stream of [resolved] declares any more, because it fell out of the set's filter
 * or TOP n since the last start. Startup reconcile only walks the declared streams, so that position would sit at
 * the venue unmanaged. Membership that stays while a position is held comes with #1714.
 */
internal fun requireHeldMembersKept(
    original: StrategyAst,
    resolved: StrategyAst,
    held: Set<String>,
) {
    val venues =
        original.streams
            .filter { it.broker == SET_BROKER }
            .map { SymbolSetSpec.parse(it.symbol) }
            .filterIsInstance<SymbolSetSpec.Filtered>()
            .mapTo(mutableSetOf()) { it.venue.uppercase() }
    val declared = resolved.streams.mapTo(mutableSetOf()) { it.qktSymbol }
    val dropped = held.filter { it !in declared && it.substringBefore(':').uppercase() in venues }
    if (dropped.isEmpty()) return
    throw BacktestContext.Companion.SetupError(
        "strategy '${original.name}' holds ${dropped.joinToString()}, which its symbol sets no longer resolve to " +
            "at start, so no rule would manage that position; close it (or add it as a stream) and start again",
    )
}
