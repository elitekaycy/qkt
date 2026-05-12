package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal

/**
 * Compiles parsed `STACK_AT` clauses into [CompiledStackTier]s the [StackEngine] consumes.
 *
 * Phase 27: numeric expressions inside a clause (threshold, sizing, bracket distances)
 * must be compile-time constant — literals or arithmetic over literals. Non-constant
 * forms (`Ref`, `StreamFieldRef`, indicators, `NOW.<field>`, ...) are rejected with a
 * clear error so strategies fail to compile rather than at the per-tick path.
 *
 * Supported clause shape:
 *   - `mfeThreshold`: any constant numeric expression
 *   - `sizing`: `SizeQty(<constant>)` only — the spec restricts STACK_AT sizing to
 *     absolute lots; percent-of-parent / risk-based sizing land in a later phase
 *   - `bracket`: both `stopLoss` and `takeProfit` must be `ChildBy(<constant>)`. Other
 *     forms (`ChildAt` / `ChildPct` / `ChildRr`) are not yet supported for stacks
 */
object StackAtCompiler {
    fun compileAll(clauses: List<StackAtClause>): List<CompiledStackTier> = clauses.map(::compile)

    fun compile(clause: StackAtClause): CompiledStackTier {
        val threshold = evalConstant(clause.mfeThreshold, context = "STACK_AT MFE threshold")
        val withinMs = clause.withinDuration.millis
        val stackQty = compileSizing(clause.sizing)
        val (sl, tp) = compileBracket(clause.bracket)
        return CompiledStackTier(
            mfeThreshold = threshold,
            withinMs = withinMs,
            stackQuantity = stackQty,
            slDistance = sl,
            tpDistance = tp,
        )
    }

    private fun compileSizing(sizing: SizingAst): BigDecimal =
        when (sizing) {
            is SizeQty -> evalConstant(sizing.expr, context = "STACK_AT SIZING")
            else ->
                error(
                    "STACK_AT only supports literal SIZING (lots); got ${sizing::class.simpleName}",
                )
        }

    private fun compileBracket(bracket: BracketAst): Pair<BigDecimal, BigDecimal> {
        val sl = bracket.stopLoss ?: error("STACK_AT BRACKET requires STOP LOSS")
        val tp = bracket.takeProfit ?: error("STACK_AT BRACKET requires TAKE PROFIT")
        return bracketDistance(sl, "STOP LOSS") to bracketDistance(tp, "TAKE PROFIT")
    }

    private fun bracketDistance(
        child: ChildPriceAst,
        leg: String,
    ): BigDecimal =
        when (child) {
            is ChildBy -> evalConstant(child.distance, context = "STACK_AT BRACKET $leg BY")
            else ->
                error(
                    "STACK_AT BRACKET $leg must use BY <distance>; got ${child::class.simpleName}",
                )
        }

    private fun evalConstant(
        expr: ExprAst,
        context: String,
    ): BigDecimal =
        when (expr) {
            is NumLit -> expr.value
            is UnaryOp ->
                when (expr.op) {
                    UnOp.NEG -> evalConstant(expr.arg, context).negate()
                    UnOp.NOT ->
                        error("$context: boolean NOT is not a numeric expression")
                }
            is BinaryOp -> evalBinary(expr, context)
            else ->
                error(
                    "$context must be a compile-time constant; got ${expr::class.simpleName}",
                )
        }

    private fun evalBinary(
        expr: BinaryOp,
        context: String,
    ): BigDecimal {
        val l = evalConstant(expr.lhs, context)
        val r = evalConstant(expr.rhs, context)
        return when (expr.op) {
            BinOp.ADD -> l.add(r)
            BinOp.SUB -> l.subtract(r)
            BinOp.MUL -> l.multiply(r)
            BinOp.DIV -> l.divide(r, com.qkt.common.Money.SCALE, com.qkt.common.Money.ROUNDING)
            BinOp.AND, BinOp.OR ->
                error("$context: boolean operator ${expr.op} is not a numeric expression")
        }
    }
}
