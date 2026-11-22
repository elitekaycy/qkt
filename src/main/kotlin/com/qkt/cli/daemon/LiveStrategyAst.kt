package com.qkt.cli.daemon

import com.qkt.cli.BacktestContext
import com.qkt.cli.DynamicSetRuns
import com.qkt.cli.SymbolSetResolution
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.persistence.StatePersistor
import com.qkt.strategy.Strategy
import java.nio.file.Path

/** How far ahead a live session's moving sets look for candidates in the listing it reads at start. */
internal const val LIVE_CANDIDATE_HORIZON_MS: Long = 24L * 60 * 60 * 1000

/**
 * [file]'s strategy as a live session runs it ([liveSets]): the declared strategy, and what runs it, a
 * [com.qkt.dsl.sets.DynamicSetHost] when it has a set whose members move (runtime membership plan, step 8).
 */
internal fun liveStrategy(
    file: Path,
    symbolSets: SetCatalog?,
    nowMs: Long,
    persistor: StatePersistor,
): Pair<StrategyAst, Strategy> {
    val sets = liveSets(file, symbolSets, nowMs, persistor)
    return sets.ast to (sets.plan?.strategy(emptyMap(), live = true) ?: AstCompiler().compile(sets.ast))
}

/** The strategy [liveStrategy] declares. */
internal fun liveStrategyAst(
    file: Path,
    symbolSets: SetCatalog?,
    nowMs: Long,
    persistor: StatePersistor,
): StrategyAst = liveSets(file, symbolSets, nowMs, persistor).ast

/**
 * [file]'s sets as a live session runs them: filtered sets resolved once from [symbolSets] (the venue listings) as of
 * [nowMs], the way a backtest resolves them from its instruments file as of `--from`; a set whose members move becomes
 * the candidates any close of the next [LIVE_CANDIDATE_HORIZON_MS] picks from that listing, stepped at each close as a
 * backtest steps them ([DynamicSetRuns]). Without a catalog the sets stay, and the compiler refuses them by name. A
 * start whose sets dropped a symbol the strategy still holds in [persistor] is refused ([requireHeldMembersKept]).
 */
private fun liveSets(
    file: Path,
    symbolSets: SetCatalog?,
    nowMs: Long,
    persistor: StatePersistor,
): DynamicSetRuns.Sets {
    val ast =
        when (val parsed = Dsl.parseFile(file)) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Failure -> {
                val msg = parsed.errors.joinToString("\n") { e -> "${e.line}:${e.col} — ${e.message}" }
                error("parse failure for $file:\n$msg")
            }
        }
    val catalog = symbolSets ?: return DynamicSetRuns.Sets(ast, null)
    val resolved = SymbolSetResolution.resolve(ast, catalog, nowMs, false, keepDynamic = true)
    val plan = DynamicSetRuns.plan(resolved, catalog, nowMs, nowMs + LIVE_CANDIDATE_HORIZON_MS, false)
    val declared = plan?.ast ?: resolved
    requireHeldMembersKept(ast, declared, heldSymbols(persistor, ast.name))
    return DynamicSetRuns.Sets(declared, plan)
}

/** The symbols [strategyId]'s persisted leg books still hold an open leg on. */
private fun heldSymbols(
    persistor: StatePersistor,
    strategyId: String,
): Set<String> =
    persistor
        .legBookSymbols(strategyId)
        .filterTo(sortedSetOf()) { symbol ->
            persistor
                .loadLegBook(strategyId, symbol)
                ?.legs
                .orEmpty()
                .isNotEmpty()
        }

/**
 * Refuses a live start that would leave a held position with no rule: a symbol on a filtered set's venue that
 * [held] still has open, and that no stream of [resolved] declares any more, because it fell out of the set's filter
 * or TOP n since the last start. Startup reconcile only walks the declared streams, so that position would sit at
 * the venue unmanaged. A moving set declares its candidates, so a held candidate is kept.
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
