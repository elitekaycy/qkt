package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackEntryRef

/**
 * Rewrites a bracket child-price AST into a form the fill-time re-anchor can evaluate.
 *
 * A bracket request carries its SL/TP ASTs so OrderManager can re-anchor the child prices
 * to the actual fill price. That re-anchor only understands literal arithmetic — an
 * indicator call (e.g. `STOP LOSS BY atr(gold.candle, 14) * 2`) would fail at fill time.
 * The freezer evaluates every non-arithmetic subexpression once, at signal-emission time,
 * and snapshots it to a numeric literal: the indicator value is frozen at entry while the
 * distance still anchors to the real fill price.
 *
 * e.g. `BY atr(gold.candle, 14) * 2` with ATR = 3.5 at entry freezes to `BY 3.5 * 2`.
 */
class ChildPriceFreezer(
    private val exprCompiler: ExprCompiler,
) {
    /** A child-price AST prepared at compile time; [freeze] snapshots it for one signal. */
    fun interface Frozen {
        /** Returns the frozen AST, or null when a snapshot value is still warming up. */
        fun freeze(ec: EvalContext): ChildPriceAst?
    }

    private fun interface FrozenExpr {
        fun freeze(ec: EvalContext): ExprAst?
    }

    fun prepare(child: ChildPriceAst): Frozen =
        when (child) {
            is ChildAt -> {
                val price = prepareExpr(child.price)
                Frozen { ec -> price.freeze(ec)?.let { child.copy(price = it) } }
            }
            is ChildBy -> {
                val distance = prepareExpr(child.distance)
                Frozen { ec -> distance.freeze(ec)?.let { child.copy(distance = it) } }
            }
            is ChildPct -> {
                val percent = prepareExpr(child.percent)
                Frozen { ec -> percent.freeze(ec)?.let { child.copy(percent = it) } }
            }
            is ChildRr -> {
                val multiplier = prepareExpr(child.multiplier)
                Frozen { ec -> multiplier.freeze(ec)?.let { child.copy(multiplier = it) } }
            }
            is ChildArmedTrail -> {
                val distance = prepareExpr(child.trailDistance)
                val threshold = prepareExpr(child.mfeThreshold)
                Frozen { ec ->
                    val d = distance.freeze(ec) ?: return@Frozen null
                    val t = threshold.freeze(ec) ?: return@Frozen null
                    child.copy(trailDistance = d, mfeThreshold = t)
                }
            }
        }

    private fun prepareExpr(expr: ExprAst): FrozenExpr =
        when {
            fillEvaluable(expr) -> FrozenExpr { expr }
            expr is BinaryOp && expr.op in ARITHMETIC -> {
                val lhs = prepareExpr(expr.lhs)
                val rhs = prepareExpr(expr.rhs)
                FrozenExpr { ec ->
                    val l = lhs.freeze(ec) ?: return@FrozenExpr null
                    val r = rhs.freeze(ec) ?: return@FrozenExpr null
                    expr.copy(lhs = l, rhs = r)
                }
            }
            else -> {
                val compiled = exprCompiler.compile(expr)
                FrozenExpr { ec -> (compiled.evaluate(ec) as? Value.Num)?.let { NumLit(it.v) } }
            }
        }

    /** True when the fill-time re-anchor can evaluate [expr] as-is (literal arithmetic). */
    private fun fillEvaluable(expr: ExprAst): Boolean =
        when (expr) {
            is NumLit, StackEntryRef -> true
            is BinaryOp -> expr.op in ARITHMETIC && fillEvaluable(expr.lhs) && fillEvaluable(expr.rhs)
            else -> false
        }

    private companion object {
        val ARITHMETIC = setOf(BinOp.ADD, BinOp.SUB, BinOp.MUL, BinOp.DIV)
    }
}
