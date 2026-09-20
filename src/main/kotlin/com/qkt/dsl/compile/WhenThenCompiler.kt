package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.WhenThen

/**
 * Compiles each `WHEN <cond> THEN <action>` rule into a [CompiledRule]: picks the stream the rule
 * evaluates on (its primary order's stream, else the one stream it reads), compiles condition and
 * `DEFAULTS`-merged action against it, and fingerprints both for the decision audit.
 */
internal object WhenThenCompiler {
    fun compileAll(
        whenThens: List<WhenThen>,
        resolvedConditions: List<ExprAst>,
        streams: Map<String, HubKey>,
        defaults: DefaultsBlock?,
        resolver: LetResolver,
        exprCompiler: ExprCompiler,
        actionCompiler: ActionCompiler,
        plan: SnapshotPlan,
        letCompiledRhs: Map<String, CompiledExpr>,
    ): List<CompiledRule> =
        whenThens.zip(resolvedConditions).mapIndexed { ruleIndex, (rule, cond) ->
            val primary: ActionAst =
                when (val a = rule.action) {
                    is Block -> a.actions.firstOrNull { it !is Log } ?: a.actions.first()
                    is OcoEntry -> a.leg1
                    else -> a
                }
            val streamAlias: String? =
                when (primary) {
                    is Buy -> primary.stream
                    is Sell -> primary.stream
                    is Close -> primary.stream
                    is Cancel -> primary.stream
                    is CloseAll, is CancelAll, is Log -> null
                    else -> null
                }
            val referencedAliases = collectStreamAliases(rule.copy(cond = cond))
            val ruleAlias =
                streamAlias
                    ?: referencedAliases.singleOrNull()
                    ?: streams.keys.firstOrNull()
                    ?: error("Strategy must declare at least one stream")
            val ruleSymbol =
                streams[ruleAlias]?.qktSymbol
                    ?: error("Unknown stream alias: $ruleAlias")
            val compiledCond = exprCompiler.compile(cond, ruleAlias = ruleAlias)
            val mergedAction = resolver.resolve(mergeDefaults(rule.action, defaults))
            val action = actionCompiler.compile(mergedAction, ruleAlias)
            val isBuy = primary is Buy
            val isSell = primary is Sell
            CompiledRule(
                condition = compiledCond,
                action = action,
                ruleAlias = ruleAlias,
                ruleSymbol = ruleSymbol,
                isBuy = isBuy,
                isSell = isSell,
                onBuyCaptures = plan.captureOnBuy.map { it to letCompiledRhs.getValue(it) },
                onSellCaptures = plan.captureOnSell.map { it to letCompiledRhs.getValue(it) },
                onOpenCaptures = plan.captureOnOpen.map { it to letCompiledRhs.getValue(it) },
                referencedAliases = referencedAliases,
                conditionFingerprint = sha256(cond.toString()),
                ruleFingerprint = sha256("$cond\n$mergedAction"),
                consumesSequenceCompletion = readsSequenceCompletion(cond),
                edgeStateKey = "$ruleAlias#$ruleIndex",
            )
        }

    private fun readsSequenceCompletion(expr: ExprAst): Boolean =
        when (expr) {
            is com.qkt.dsl.ast.SequenceAccessor -> expr.stage == null && expr.field == "complete"
            is com.qkt.dsl.ast.BinaryOp -> readsSequenceCompletion(expr.lhs) || readsSequenceCompletion(expr.rhs)
            is com.qkt.dsl.ast.UnaryOp -> readsSequenceCompletion(expr.arg)
            is com.qkt.dsl.ast.CmpOp -> readsSequenceCompletion(expr.lhs) || readsSequenceCompletion(expr.rhs)
            is com.qkt.dsl.ast.Crosses -> readsSequenceCompletion(expr.lhs) || readsSequenceCompletion(expr.rhs)
            is com.qkt.dsl.ast.FuncCall -> expr.args.any(::readsSequenceCompletion)
            is com.qkt.dsl.ast.IndicatorCall -> expr.args.any(::readsSequenceCompletion)
            is com.qkt.dsl.ast.Aggregate -> readsSequenceCompletion(expr.series)
            is com.qkt.dsl.ast.Between ->
                readsSequenceCompletion(expr.v) ||
                    readsSequenceCompletion(expr.lo) ||
                    readsSequenceCompletion(expr.hi)
            is com.qkt.dsl.ast.InList ->
                readsSequenceCompletion(expr.v) || expr.members.any(::readsSequenceCompletion)
            is com.qkt.dsl.ast.CaseWhen ->
                expr.branches.any { readsSequenceCompletion(it.first) || readsSequenceCompletion(it.second) } ||
                    readsSequenceCompletion(expr.elseExpr)
            is com.qkt.dsl.ast.IsNull -> readsSequenceCompletion(expr.expr)
            else -> false
        }
}
