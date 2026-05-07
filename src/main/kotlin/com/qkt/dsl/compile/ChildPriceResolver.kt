package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import java.math.BigDecimal

enum class ChildKind { STOP_LOSS, TAKE_PROFIT }

fun interface CompiledChildPrice {
    fun evaluate(
        ec: EvalContext,
        side: Side,
        entry: BigDecimal,
        stopDistance: BigDecimal?,
    ): BigDecimal
}

class ChildPriceResolver(
    private val exprCompiler: ExprCompiler,
) {
    fun compile(
        child: ChildPriceAst,
        kind: ChildKind,
    ): CompiledChildPrice =
        when (child) {
            is ChildAt -> {
                val priceExpr = exprCompiler.compile(child.price)
                CompiledChildPrice { ec, _, _, _ ->
                    val v = priceExpr.evaluate(ec)
                    require(v is Value.Num) { "child AT expression must be numeric" }
                    v.v
                }
            }
            is ChildBy -> {
                val distExpr = exprCompiler.compile(child.distance)
                CompiledChildPrice { ec, side, entry, _ ->
                    val v = distExpr.evaluate(ec)
                    require(v is Value.Num) { "child BY expression must be numeric" }
                    applyDistance(side, entry, v.v, kind)
                }
            }
            is ChildPct -> {
                val fracExpr = exprCompiler.compile(child.frac)
                CompiledChildPrice { ec, side, entry, _ ->
                    val v = fracExpr.evaluate(ec)
                    require(v is Value.Num) { "child PCT expression must be numeric" }
                    val dist = entry.multiply(v.v, Money.CONTEXT)
                    applyDistance(side, entry, dist, kind)
                }
            }
            is ChildRr -> {
                require(kind == ChildKind.TAKE_PROFIT) {
                    "RR child price mode is only valid for TAKE PROFIT (got $kind)"
                }
                val multExpr = exprCompiler.compile(child.multiplier)
                CompiledChildPrice { ec, side, entry, stopDistance ->
                    val sd =
                        stopDistance
                            ?: error("RR take-profit requires a resolvable stop distance from BRACKET STOP LOSS")
                    val v = multExpr.evaluate(ec)
                    require(v is Value.Num) { "child RR expression must be numeric" }
                    applyDistance(side, entry, v.v.multiply(sd, Money.CONTEXT), ChildKind.TAKE_PROFIT)
                }
            }
        }

    private fun applyDistance(
        side: Side,
        entry: BigDecimal,
        dist: BigDecimal,
        kind: ChildKind,
    ): BigDecimal {
        val sign =
            when {
                kind == ChildKind.STOP_LOSS && side == Side.BUY -> -BigDecimal.ONE
                kind == ChildKind.STOP_LOSS && side == Side.SELL -> BigDecimal.ONE
                kind == ChildKind.TAKE_PROFIT && side == Side.BUY -> BigDecimal.ONE
                kind == ChildKind.TAKE_PROFIT && side == Side.SELL -> -BigDecimal.ONE
                else -> error("unreachable")
            }
        return entry.add(sign.multiply(dist, Money.CONTEXT), Money.CONTEXT)
    }
}
