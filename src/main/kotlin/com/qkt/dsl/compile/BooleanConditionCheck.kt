package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp

/**
 * Rejects a `WHEN` condition, or an `AND`/`OR`/`NOT` operand of one, that can only be a number or
 * text: `WHEN gold.close` or `WHEN rsi(gold, 14)` is never true, so the rule would silently never fire.
 */
internal fun rejectNonBooleanCondition(cond: ExprAst) {
    when {
        cond is BinaryOp && (cond.op == BinOp.AND || cond.op == BinOp.OR) -> {
            rejectNonBooleanCondition(cond.lhs)
            rejectNonBooleanCondition(cond.rhs)
        }
        cond is UnaryOp && cond.op == UnOp.NOT -> rejectNonBooleanCondition(cond.arg)
        else -> {
            val value =
                cond is NumLit ||
                    cond is StringLit ||
                    cond is IndicatorCall ||
                    cond is StreamFieldRef ||
                    cond is BinaryOp ||
                    cond is UnaryOp
            require(!value) {
                "WHEN needs a test, not a value: compare it (>, CROSSES, IN, BETWEEN, IS NULL) or combine tests"
            }
        }
    }
}
