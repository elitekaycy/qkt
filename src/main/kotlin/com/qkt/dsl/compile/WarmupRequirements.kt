package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen

/**
 * Derives how many closed bars per stream alias a strategy needs to be warm.
 *
 * Combines three sources, taking the max per alias:
 * - Explicit `WARMUP N BARS` on the `StreamDecl`.
 * - Indicator warmup, read from the indicator's own [com.qkt.indicators.IndicatorOutput.warmupBars]
 *   via the registry (exact for multi-window indicators — MACD(12,26,9) needs 34
 *   bars, not 26). Walks every rule CONDITION, every rule ACTION (sizing, order
 *   prices, bracket/OCO child prices, stack specs — `BRACKET ... BY atr(...)` counts),
 *   and every `LET` expression, with strategy DEFAULTS merged in first.
 *
 * Chained indicators (e.g. `EMA(EMA(close, 9), 21)`) report the outer period only;
 * use an explicit `WARMUP N BARS` to override when the true warmup exceeds the
 * outer period.
 *
 * Lookback indices (`stream.close[N]`) are not yet derived — set explicit
 * `WARMUP N BARS` to cover them.
 */
object WarmupRequirements {
    fun compute(ast: StrategyAst): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (s in ast.streams) {
            val w = s.warmupBars ?: 0
            if (w > 0) merge(out, s.alias, w)
        }
        for (rule in ast.rules) walkRule(rule, ast, out)
        for (let in ast.lets) walkExpr(let.expr, out)
        return out.toMap()
    }

    private fun walkRule(
        rule: RuleAst,
        ast: StrategyAst,
        out: MutableMap<String, Int>,
    ) {
        if (rule !is WhenThen) return
        walkExpr(rule.cond, out)
        // Action-side indicators warm the gate too: an ATR inside a bracket child
        // price computes garbage on a half-warm window exactly like one in the
        // condition. DEFAULTS merge first so `DEFAULTS { STOP_LOSS = BY ATR(...) }`
        // counts for every action it applies to.
        walkAction(mergeDefaults(rule.action, ast.defaults), out)
    }

    private fun walkAction(
        action: ActionAst,
        out: MutableMap<String, Int>,
    ) {
        when (action) {
            is Buy -> walkOpts(action.opts, out)
            is Sell -> walkOpts(action.opts, out)
            is Block -> action.actions.forEach { walkAction(it, out) }
            is OcoEntry -> {
                walkAction(action.leg1, out)
                walkAction(action.leg2, out)
            }
            else -> Unit
        }
    }

    private fun walkOpts(
        opts: ActionOpts,
        out: MutableMap<String, Int>,
    ) {
        opts.sizing?.let { walkSizing(it, out) }
        opts.orderType?.let { walkOrderType(it, out) }
        opts.tif?.let { if (it is Gtd) walkExpr(it.until, out) }
        opts.bracket?.stopLoss?.let { walkChildPrice(it, out) }
        opts.bracket?.takeProfit?.let { walkChildPrice(it, out) }
        opts.oco?.let {
            walkChildPrice(it.stop, out)
            walkChildPrice(it.limit, out)
        }
        when (val stack = opts.stack) {
            is StackSpacing -> walkExpr(stack.spacing, out)
            is StackLayers ->
                for (layer in stack.layers) {
                    walkSizing(layer.sizing, out)
                    layer.orderType?.let { walkOrderType(it, out) }
                    layer.at?.let { walkExpr(it, out) }
                }
            null -> Unit
        }
        for (tier in opts.stackAts) {
            walkExpr(tier.mfeThreshold, out)
            walkSizing(tier.sizing, out)
            tier.bracket.stopLoss?.let { walkChildPrice(it, out) }
            tier.bracket.takeProfit?.let { walkChildPrice(it, out) }
        }
        // OTO (ON_FILL) children warm the gate too — an indicator in a child's price computes
        // garbage on a half-warm window exactly like one in the parent.
        opts.onFill.forEach { walkAction(it, out) }
    }

    private fun walkSizing(
        sizing: SizingAst,
        out: MutableMap<String, Int>,
    ) {
        when (sizing) {
            is SizeQty -> walkExpr(sizing.expr, out)
            is SizeNotional -> walkExpr(sizing.usd, out)
            is SizeRiskAbs -> walkExpr(sizing.usd, out)
            is SizeRiskFrac -> walkExpr(sizing.frac, out)
            is SizePctEquity -> walkExpr(sizing.frac, out)
            is SizePctBalance -> walkExpr(sizing.frac, out)
            else -> Unit
        }
    }

    private fun walkOrderType(
        ot: OrderTypeAst,
        out: MutableMap<String, Int>,
    ) {
        when (ot) {
            is Limit -> walkExpr(ot.price, out)
            is Stop -> walkExpr(ot.price, out)
            is StopLimit -> {
                walkExpr(ot.stopPrice, out)
                walkExpr(ot.limitPrice, out)
            }
            is TrailingBy -> walkExpr(ot.distance, out)
            is TrailingPct -> walkExpr(ot.frac, out)
            else -> Unit
        }
    }

    private fun walkChildPrice(
        cp: ChildPriceAst,
        out: MutableMap<String, Int>,
    ) {
        when (cp) {
            is ChildAt -> walkExpr(cp.price, out)
            is ChildBy -> walkExpr(cp.distance, out)
            is ChildPct -> walkExpr(cp.frac, out)
            is ChildRr -> walkExpr(cp.multiplier, out)
            is ChildArmedTrail -> {
                walkExpr(cp.trailDistance, out)
                walkExpr(cp.mfeThreshold, out)
            }
        }
    }

    private fun walkExpr(
        expr: ExprAst,
        out: MutableMap<String, Int>,
    ) {
        when (expr) {
            is IndicatorCall -> {
                val alias = aliasFor(expr)
                val period = registryWarmupBars(expr) ?: numLitMax(expr)
                if (alias != null && period != null) merge(out, alias, period)
                expr.args.forEach { walkExpr(it, out) }
            }
            is BinaryOp -> {
                walkExpr(expr.lhs, out)
                walkExpr(expr.rhs, out)
            }
            is UnaryOp -> walkExpr(expr.arg, out)
            is CmpOp -> {
                walkExpr(expr.lhs, out)
                walkExpr(expr.rhs, out)
            }
            is Between -> {
                walkExpr(expr.v, out)
                walkExpr(expr.lo, out)
                walkExpr(expr.hi, out)
            }
            is InList -> {
                walkExpr(expr.v, out)
                expr.members.forEach { walkExpr(it, out) }
            }
            is Crosses -> {
                walkExpr(expr.lhs, out)
                walkExpr(expr.rhs, out)
            }
            is CaseWhen -> {
                expr.branches.forEach { (c, v) ->
                    walkExpr(c, out)
                    walkExpr(v, out)
                }
                walkExpr(expr.elseExpr, out)
            }
            is Aggregate -> walkExpr(expr.series, out)
            is FuncCall -> expr.args.forEach { walkExpr(it, out) }
            is IsNull -> walkExpr(expr.expr, out)
            else -> Unit
        }
    }

    /**
     * The indicator's true warmup, read from a registry-built instance — exact for
     * multi-window indicators where the max literal undercounts (MACD(12,26,9) is 34
     * bars, HIGHEST(N) is N+1). Null when the call shape doesn't match the spec; the
     * caller falls back to [numLitMax].
     */
    private fun registryWarmupBars(call: IndicatorCall): Int? {
        val spec =
            com.qkt.dsl.stdlib.IndicatorRegistry
                .spec(call.name) ?: return null
        val consts =
            call.args
                .drop(spec.seriesCount)
                .filterIsInstance<NumLit>()
                .map { it.value }
        if (consts.size != spec.arity - spec.seriesCount) return null
        return runCatching {
            com.qkt.dsl.stdlib.IndicatorRegistry
                .create(call.name, consts)
                .warmupBars
        }.getOrNull()
    }

    private fun numLitMax(call: IndicatorCall): Int? =
        call.args
            .drop(1)
            .filterIsInstance<NumLit>()
            .maxOfOrNull { it.value.toInt() }

    private fun aliasFor(call: IndicatorCall): String? {
        val first = call.args.firstOrNull() ?: return null
        return when (first) {
            is StreamFieldRef -> first.stream
            is IndicatorCall -> aliasFor(first)
            else -> null
        }
    }

    private fun merge(
        out: MutableMap<String, Int>,
        alias: String,
        bars: Int,
    ) {
        out[alias] = maxOf(out[alias] ?: 0, bars)
    }
}
