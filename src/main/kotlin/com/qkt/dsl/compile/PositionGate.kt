package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef

/**
 * What a rule's condition requires of the position on the rule's own stream, when that can be
 * read off the condition without evaluating it.
 *
 * Rules fire on a false-to-true edge and are evaluated at bar close. A position can open and
 * close again between two evaluations, so a condition gated on the position state is false for
 * a while with nobody looking: true at both evaluations, no edge, and the rule never fires again
 * (#1194). When the gate is a top-level term of a conjunction, the whole condition is false for
 * as long as that term is — so the edge can be reset the moment the position state flips, with no
 * evaluation (which would disturb stateful operators such as `CROSSES`).
 *
 * e.g. on a rule for stream `btc`: `close > ema AND POSITION.btc = 0` is [FLAT];
 * `POSITION.btc != 0 AND rsi < 40` is [HELD]; `close > ema OR POSITION.btc = 0`,
 * `POSITION.eth = 0` and `NOT (POSITION.btc = 0)` are [NONE] and keep bar-close semantics only.
 */
enum class PositionGate {
    /** No statically known dependence on the position state. */
    NONE,

    /** False while the strategy holds the symbol: reset when a position opens. */
    FLAT,

    /** False while the strategy is flat on the symbol: reset when the position closes. */
    HELD,
    ;

    companion object {
        fun of(
            condition: ExprAst,
            ruleAlias: String,
        ): PositionGate {
            val terms = conjuncts(condition).mapNotNull { gateOf(it, ruleAlias) }
            return terms.firstOrNull() ?: NONE
        }

        private fun conjuncts(expr: ExprAst): List<ExprAst> =
            if (expr is BinaryOp && expr.op == BinOp.AND) conjuncts(expr.lhs) + conjuncts(expr.rhs) else listOf(expr)

        private fun gateOf(
            expr: ExprAst,
            ruleAlias: String,
        ): PositionGate? {
            if (expr !is CmpOp) return null
            val positionOnLeft = isPositionOf(expr.lhs, ruleAlias) && isZero(expr.rhs)
            val positionOnRight = isZero(expr.lhs) && isPositionOf(expr.rhs, ruleAlias)
            if (!positionOnLeft && !positionOnRight) return null
            return when (expr.op) {
                Cmp.EQ -> FLAT
                // `> 0`, `< 0` and `!= 0` are all false on a flat book, whichever side zero is written.
                Cmp.NE, Cmp.GT, Cmp.LT -> HELD
                // `>= 0` and `<= 0` are true when flat and may be true when held: not a gate.
                Cmp.GE, Cmp.LE -> null
            }
        }

        private fun isPositionOf(
            expr: ExprAst,
            ruleAlias: String,
        ): Boolean = expr is PositionRef && expr.stream == ruleAlias

        private fun isZero(expr: ExprAst): Boolean = expr is NumLit && expr.value.signum() == 0
    }
}
