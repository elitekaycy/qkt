package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef

/**
 * Recognises the documented re-entry pattern: a condition that is a conjunction with
 * `POSITION.<stream> = 0` for the rule's own stream as one of its top-level terms.
 *
 * Such a condition is false for as long as the strategy holds that symbol, by construction, so
 * the edge can be reset the moment a position opens without evaluating anything. Conditions that
 * mention the position any other way (`OR`, `>=`, another stream, inside a function) are left to
 * ordinary bar-close evaluation: their truth while a position is open is not known statically.
 *
 * e.g. `close > ema AND POSITION.btc = 0` on a `BUY btc` rule is a flat gate;
 * `close > ema OR POSITION.btc = 0` and `POSITION.eth = 0` on a `BUY btc` rule are not.
 */
internal object FlatGate {
    fun requiresFlat(
        condition: ExprAst,
        ruleAlias: String,
    ): Boolean = conjuncts(condition).any { isFlatTerm(it, ruleAlias) }

    private fun conjuncts(expr: ExprAst): List<ExprAst> =
        if (expr is BinaryOp && expr.op == BinOp.AND) conjuncts(expr.lhs) + conjuncts(expr.rhs) else listOf(expr)

    private fun isFlatTerm(
        expr: ExprAst,
        ruleAlias: String,
    ): Boolean {
        if (expr !is CmpOp || expr.op != Cmp.EQ) return false
        return isPositionOf(expr.lhs, ruleAlias) &&
            isZero(expr.rhs) ||
            isZero(expr.lhs) &&
            isPositionOf(expr.rhs, ruleAlias)
    }

    private fun isPositionOf(
        expr: ExprAst,
        ruleAlias: String,
    ): Boolean = expr is PositionRef && expr.stream == ruleAlias

    private fun isZero(expr: ExprAst): Boolean = expr is NumLit && expr.value.signum() == 0
}
