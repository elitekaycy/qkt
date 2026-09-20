package com.qkt.cli

import com.qkt.cli.requirements.ActionExpressionVisitor
import com.qkt.cli.requirements.ExprDataRequirementCollector
import com.qkt.dsl.ast.StrategyAst

/** The stream aliases a strategy reads quotes or volume from, so data provisioning can require them. */
internal data class StrategyDataRequirements(
    val quoteAliases: Set<String>,
    val volumeAliases: Set<String>,
)

/** Scans a parsed strategy for the quote and volume data its expressions read. */
internal object StrategyDataRequirementScanner {
    /** Walk every let, param, rule, schedule and sequence stage of [ast]. */
    fun scan(ast: StrategyAst): StrategyDataRequirements {
        val collector = ExprDataRequirementCollector()
        val walk = collector::walk
        val actions = ActionExpressionVisitor(walk)
        ast.lets.forEach { walk(it.expr) }
        ast.params.forEach { walk(it.value) }
        ast.rules.forEach(actions::walkRule)
        ast.schedules.forEach { actions.walkAction(it.action) }
        ast.sequences.forEach { sequence -> sequence.stages.forEach { walk(it.condition) } }
        return StrategyDataRequirements(
            quoteAliases = collector.quoteAliases,
            volumeAliases = collector.volumeAliases,
        )
    }
}
