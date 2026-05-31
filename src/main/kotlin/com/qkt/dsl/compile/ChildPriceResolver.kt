package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.StopLossSpec
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

/**
 * Output of [ChildPriceResolver.compileStopLoss] — bracket stop loss as either an
 * engine-managed [StopLossSpec.ArmedTrail] (resolved entirely at compile time, no
 * per-tick evaluation needed) or a [Dynamic] that produces a [StopLossSpec.Fixed]
 * at submission time given the entry price.
 */
sealed interface CompiledStopLoss {
    fun interface Dynamic : CompiledStopLoss {
        fun evaluate(
            ec: EvalContext,
            side: Side,
            entry: BigDecimal,
        ): StopLossSpec.Fixed
    }

    data class Static(
        val spec: StopLossSpec,
    ) : CompiledStopLoss
}

class ChildPriceResolver(
    private val exprCompiler: ExprCompiler,
) {
    /**
     * Compile a bracket stop-loss leg. Returns [CompiledStopLoss.Static] when the
     * leg is an engine-managed armed trail (no per-tick price resolution required),
     * or [CompiledStopLoss.Dynamic] for the price-resolving variants (`AT`, `BY`, `PCT`).
     * `RR` is rejected because it's a take-profit-only form.
     */
    fun compileStopLoss(child: ChildPriceAst): CompiledStopLoss =
        when (child) {
            is ChildArmedTrail -> {
                require(child.trailDistance is NumLit) {
                    "TRAILING <distance> must be a numeric literal; got ${child.trailDistance::class.simpleName}"
                }
                require(child.mfeThreshold is NumLit) {
                    "AFTER MFE >= <threshold> must be a numeric literal; got ${child.mfeThreshold::class.simpleName}"
                }
                CompiledStopLoss.Static(
                    StopLossSpec.ArmedTrail(
                        trailDistance = (child.trailDistance as NumLit).value,
                        mfeThreshold = (child.mfeThreshold as NumLit).value,
                    ),
                )
            }
            is ChildRr -> error("ChildRr is only valid for TAKE PROFIT, not STOP LOSS")
            else -> {
                val priced = compile(child, ChildKind.STOP_LOSS)
                CompiledStopLoss.Dynamic { ec, side, entry ->
                    StopLossSpec.Fixed(priced.evaluate(ec, side, entry, stopDistance = null))
                }
            }
        }

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
            is ChildArmedTrail -> {
                // The armed-trail variant emits an engine-managed dynamic stop, not a
                // static price evaluated here. Task 5 wires the proper StopLossSpec.ArmedTrail
                // path through ActionCompiler so this branch is never invoked for armed
                // trails. The pre-arm stop level (entry ± distance) is computed at
                // bracket-fill time by OrderManager. See #48 plan, Task 5.
                require(kind == ChildKind.STOP_LOSS) {
                    "ChildArmedTrail is only valid for STOP LOSS (got $kind)"
                }
                val distExpr = exprCompiler.compile(child.trailDistance)
                CompiledChildPrice { ec, side, entry, _ ->
                    val v = distExpr.evaluate(ec)
                    require(v is Value.Num) { "TRAILING <distance> must be numeric" }
                    applyDistance(side, entry, v.v, ChildKind.STOP_LOSS)
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
