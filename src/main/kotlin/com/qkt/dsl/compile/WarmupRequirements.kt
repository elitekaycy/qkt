package com.qkt.dsl.compile

import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen

/**
 * Derives how many closed bars per stream alias a strategy needs to be warm.
 *
 * Combines three sources, taking the max per alias:
 * - Explicit `WARMUP N BARS` on the `StreamDecl`.
 * - Indicator periods (e.g. `EMA(stream.close, 50)` → 50). Walks the AST of every
 *   rule condition and every `LET` expression.
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
        for (rule in ast.rules) walkRule(rule, out)
        for (let in ast.lets) walkExpr(let.expr, out)
        return out.toMap()
    }

    private fun walkRule(
        rule: RuleAst,
        out: MutableMap<String, Int>,
    ) {
        if (rule is WhenThen) walkExpr(rule.cond, out)
    }

    private fun walkExpr(
        expr: ExprAst,
        out: MutableMap<String, Int>,
    ) {
        when (expr) {
            is IndicatorCall -> {
                val alias = aliasFor(expr)
                val period =
                    expr.args
                        .drop(1)
                        .filterIsInstance<NumLit>()
                        .maxOfOrNull { it.value.toInt() }
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
