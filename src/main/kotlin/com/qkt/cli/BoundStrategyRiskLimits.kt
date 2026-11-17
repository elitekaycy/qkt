package com.qkt.cli

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.sets.SetMemberAlias
import com.qkt.risk.StrategyRiskLimits

/**
 * The replay's per-strategy limits from the config's `risk.per_strategy` entries, with each strategy's symbol-set
 * caps bound to the members its parsed [ast] declares ([SetMemberAlias.membersOf]). A cap naming a set the strategy
 * does not declare fails the run by name rather than guarding nothing.
 */
internal fun backtestStrategyRiskLimits(
    perStrategyRisk: Map<String, PerStrategyRisk>,
    ast: StrategyAst,
): Map<String, StrategyRiskLimits> {
    val members = SetMemberAlias.membersOf(ast.streams)
    return perStrategyRisk.mapValues { (strategy, limits) -> bound(strategy, limits, members) }
}

/**
 * A live [strategy]'s `risk.per_strategy` entry [risk] as the limits its session enforces, symbol-set caps bound to
 * the members its [streams] declare exactly as a backtest binds them: a cap naming a set the strategy does not declare
 * (or any set, for one that declares none, such as a `qkt bot session` identity) refuses the start by name rather
 * than guarding nothing. Null when the strategy has no entry. A set whose membership moves during the run is in
 * [dynamicSets], by alias, with the supplier of its active members (sticky-held ones included): its caps count those at
 * each order check, and are valid before any member stream exists (#1714 step 9).
 */
internal fun liveRiskLimits(
    strategy: String,
    risk: PerStrategyRisk?,
    streams: List<StreamDecl>,
    dynamicSets: Map<String, () -> Set<String>> = emptyMap(),
): StrategyRiskLimits? = risk?.let { bound(strategy, it, SetMemberAlias.membersOf(streams), dynamicSets) }

private fun bound(
    strategy: String,
    limits: PerStrategyRisk,
    members: Map<String, Set<String>>,
    dynamicSets: Map<String, () -> Set<String>> = emptyMap(),
): StrategyRiskLimits =
    runCatching { limits.toLimits(members, dynamicSets) }.getOrElse { e ->
        if (e !is IllegalStateException) throw e
        error("risk.per_strategy.$strategy.${e.message}")
    }
