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
import com.qkt.dsl.ast.OPTIONS_BROKER
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
            compilingRule(rule) {
                compileOne(
                    ruleIndex,
                    rule,
                    cond,
                    streams,
                    defaults,
                    resolver,
                    exprCompiler,
                    actionCompiler,
                    plan,
                    letCompiledRhs,
                )
            }
        }

    private fun compileOne(
        ruleIndex: Int,
        rule: WhenThen,
        cond: ExprAst,
        streams: Map<String, HubKey>,
        defaults: DefaultsBlock?,
        resolver: LetResolver,
        exprCompiler: ExprCompiler,
        actionCompiler: ActionCompiler,
        plan: SnapshotPlan,
        letCompiledRhs: Map<String, CompiledExpr>,
    ): CompiledRule {
        val primary: ActionAst =
            when (val a = rule.action) {
                is Block -> a.actions.firstOrNull { it !is Log } ?: a.actions.first()
                is OcoEntry -> a.leg1
                else -> a
            }
        // A structure alias (`OPEN ps = …`) is not a stream: its rule reads and closes the structure.
        val structures = exprCompiler.structures.aliases
        val streamAlias: String? =
            when (primary) {
                is Buy -> primary.stream
                is Sell -> primary.stream
                is Close -> primary.stream.takeUnless { it in structures }
                is Cancel -> primary.stream
                is CloseAll, is CancelAll, is Log -> null
                else -> null
            }
        val referencedAliases = collectStreamAliases(rule.copy(cond = cond)) - structures
        // Every alias a rule touches must be declared, whatever else the rule reads: an
        // undeclared one used to fail only at first evaluation (or never, when another
        // alias became the rule's stream).
        referencedAliases.firstOrNull { it !in streams }?.let { error("Unknown stream alias: $it") }
        val runsOn = RuleTriggers.runsOn(referencedAliases, streams)
        // A rule that reads no stream runs on the first one with candles: an OPTIONS: root feed has none
        // under its own alias (its ticks carry contract symbols), so a rule bound to it would never run.
        val ruleAlias =
            streamAlias
                ?: runsOn.singleOrNull()
                ?: streams.entries.firstOrNull { !it.value.broker.equals(OPTIONS_BROKER, ignoreCase = true) }?.key
                ?: error("rule needs a stream with candles to run on; an OPTIONS: feed has no candles")
        // A rule acting on no stream runs on every close of the streams it reads, or of every stream with
        // candles when it reads none (an ACCOUNT kill switch must not wait for one market to reopen).
        val triggerAliases =
            when {
                streamAlias != null || runsOn.size == 1 -> setOf(ruleAlias)
                runsOn.isNotEmpty() -> runsOn
                else -> streams.filterValues { !it.broker.equals(OPTIONS_BROKER, ignoreCase = true) }.keys
            }
        val ruleSymbol =
            streams[ruleAlias]?.qktSymbol
                ?: error("Unknown stream alias: $ruleAlias")
        val compiledCond = exprCompiler.compile(cond, ruleAlias = ruleAlias)
        val mergedAction = resolver.resolve(mergeDefaults(rule.action, defaults))
        val action = actionCompiler.compile(mergedAction, ruleAlias)
        val isBuy = primary is Buy
        val isSell = primary is Sell
        return CompiledRule(
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
            positionGate = PositionGate.of(cond, ruleAlias),
            triggerAliases = triggerAliases,
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
