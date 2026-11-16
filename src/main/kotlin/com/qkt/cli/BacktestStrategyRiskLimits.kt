package com.qkt.cli

import com.qkt.dsl.ast.StrategyAst
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
    return perStrategyRisk.mapValues { (strategy, limits) ->
        try {
            limits.toLimits(members)
        } catch (e: IllegalStateException) {
            error("risk.per_strategy.$strategy.${e.message}")
        }
    }
}
