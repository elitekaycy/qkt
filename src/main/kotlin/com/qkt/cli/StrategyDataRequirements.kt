package com.qkt.cli

import com.qkt.cli.requirements.ActionExpressionVisitor
import com.qkt.cli.requirements.ExprDataRequirementCollector
import com.qkt.dsl.ast.StrategyAst
import com.qkt.marketdata.flow.FlowKind

/** The stream aliases a strategy reads quotes, volume, marks or trade flow from, so data provisioning can require them. */
internal data class StrategyDataRequirements(
    val quoteAliases: Set<String>,
    val volumeAliases: Set<String>,
    val markAliases: Set<String> = emptySet(),
    val flowLookbacks: Map<Pair<String, FlowKind>, Int> = emptyMap(),
)

/** Scans a parsed strategy for the quote, volume and mark data its expressions read. */
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
            markAliases = collector.markAliases,
            flowLookbacks = collector.flowLookbacks,
        )
    }
}
