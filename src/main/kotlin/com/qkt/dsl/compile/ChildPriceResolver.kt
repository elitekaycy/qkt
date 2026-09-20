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
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.TimeTightenAst
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal

/**
 * Compiles bracket child prices (`AT`, `BY`, `PCT`, `RR`, armed trail) into closures that resolve
 * against the entry side and price, and stop-loss legs into a static or dynamic [StopLossSpec].
 */
class ChildPriceResolver(
    private val exprCompiler: ExprCompiler,
) {
    private val stopSpecs = StopSpecCompiler(exprCompiler)

    /**
     * Compile a bracket stop-loss leg. Returns [CompiledStopLoss.Static] when the
     * leg is an engine-managed armed trail (no per-tick price resolution required),
     * or [CompiledStopLoss.Dynamic] for the price-resolving variants (`AT`, `BY`, `PCT`).
     * `RR` is rejected because it's a take-profit-only form.
     */
    fun compileStopLoss(
        child: ChildPriceAst,
        /**
         * Whether trail and ratchet distances may be expressions (`TRAILING atr(x, 14) * 2`),
         * evaluated once when the order is built (#1116). STACK brackets keep literals: their
         * layer fills re-read the bracket AST, which does not carry evaluated ratchet operands.
         */
        allowExpressionDistances: Boolean = true,
    ): CompiledStopLoss =
        when (child) {
            is ChildArmedTrail -> {
                val operands = listOf(child.trailDistance, child.mfeThreshold)
                val labels = listOf("TRAILING <distance>", "AFTER MFE >= <threshold>")
                stopSpecs.specFrom(operands, labels, allowExpressionDistances) { v ->
                    StopLossSpec.ArmedTrail(trailDistance = v[0], mfeThreshold = v[1])
                }
            }
            is ChildBy -> {
                when (val ratchet = child.ratchet) {
                    null -> {
                        val priced = compile(child, ChildKind.STOP_LOSS)
                        CompiledStopLoss.Dynamic { ec, side, entry ->
                            priced.evaluate(ec, side, entry, stopDistance = null)?.let { StopLossSpec.Fixed(it) }
                        }
                    }
                    is SteppedStopAst -> {
                        val operands =
                            listOf(child.distance) +
                                ratchet.steps.flatMap { listOf(it.mfeThreshold, it.profitDistance) }
                        val labels =
                            listOf("STOP LOSS BY <distance>") +
                                ratchet.steps.indices.flatMap {
                                    listOf("step ${it + 1} MFE threshold", "step ${it + 1} target")
                                }
                        stopSpecs.specFrom(operands, labels, allowExpressionDistances) { v ->
                            StopLossSpec.SteppedStop(
                                initialDistance = v[0],
                                steps = ratchet.steps.indices.map { StopLossSpec.Step(v[1 + 2 * it], v[2 + 2 * it]) },
                            )
                        }
                    }
                    is TimeTightenAst -> {
                        val operands = listOf(child.distance, ratchet.tightenBy, ratchet.floorDistance)
                        val labels = listOf("STOP LOSS BY <distance>", "TIGHTEN BY <distance>", "FLOOR <distance>")
                        stopSpecs.specFrom(operands, labels, allowExpressionDistances) { v ->
                            StopLossSpec.TimeTighten(
                                initialDistance = v[0],
                                tightenBy = v[1],
                                intervalMs = ratchet.interval.millis,
                                floorDistance = v[2],
                            )
                        }
                    }
                }
            }
            is ChildRr -> error("ChildRr is only valid for TAKE PROFIT, not STOP LOSS")
            else -> {
                val priced = compile(child, ChildKind.STOP_LOSS)
                CompiledStopLoss.Dynamic { ec, side, entry ->
                    priced.evaluate(ec, side, entry, stopDistance = null)?.let { StopLossSpec.Fixed(it) }
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
                    priceExpr.evaluateNumber(ec)
                }
            }
            is ChildBy -> {
                require(child.ratchet == null || kind == ChildKind.STOP_LOSS) {
                    "stop ratchets are only valid for STOP LOSS"
                }
                val distExpr = exprCompiler.compile(child.distance)
                CompiledChildPrice { ec, side, entry, _ ->
                    val v = distExpr.evaluateNumber(ec) ?: return@CompiledChildPrice null
                    applyDistance(side, entry, v, kind)
                }
            }
            is ChildPct -> {
                if (child.percent is NumLit) {
                    BracketPercent.fraction(child.percent.value, kind == ChildKind.STOP_LOSS)
                }
                val percentExpr = exprCompiler.compile(child.percent)
                CompiledChildPrice { ec, side, entry, _ ->
                    val percent = percentExpr.evaluateNumber(ec) ?: return@CompiledChildPrice null
                    val fraction = BracketPercent.fraction(percent, kind == ChildKind.STOP_LOSS)
                    val dist = entry.multiply(fraction, Money.CONTEXT)
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
                    val v = multExpr.evaluateNumber(ec) ?: return@CompiledChildPrice null
                    applyDistance(side, entry, v.multiply(sd, Money.CONTEXT), ChildKind.TAKE_PROFIT)
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
                    val v = distExpr.evaluateNumber(ec) ?: return@CompiledChildPrice null
                    applyDistance(side, entry, v, ChildKind.STOP_LOSS)
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

    private fun CompiledExpr.evaluateNumber(ec: EvalContext): BigDecimal? = (evaluate(ec) as? Value.Num)?.v
}
