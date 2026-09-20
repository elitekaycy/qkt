package com.qkt.dsl.compile

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.StopStepAst
import com.qkt.dsl.ast.TimeTightenAst

/**
 * The [ExprTransform] rewrite of bracket and OCO child prices, including a BY child's stepped
 * or time-tightening ratchet. Every expression inside goes back through [ExprTransform.expr].
 */
internal class ChildPriceTransform(
    private val exprs: ExprTransform,
) {
    fun childPrice(cp: ChildPriceAst): ChildPriceAst =
        when (cp) {
            is ChildAt -> ChildAt(exprs.expr(cp.price))
            is ChildBy ->
                ChildBy(
                    distance = exprs.expr(cp.distance),
                    ratchet =
                        when (val ratchet = cp.ratchet) {
                            null -> null
                            is SteppedStopAst ->
                                SteppedStopAst(
                                    ratchet.steps.map {
                                        StopStepAst(
                                            mfeThreshold = exprs.expr(it.mfeThreshold),
                                            profitDistance = exprs.expr(it.profitDistance),
                                        )
                                    },
                                )
                            is TimeTightenAst ->
                                TimeTightenAst(
                                    tightenBy = exprs.expr(ratchet.tightenBy),
                                    interval = ratchet.interval,
                                    floorDistance = exprs.expr(ratchet.floorDistance),
                                )
                        },
                )
            is ChildPct -> ChildPct(exprs.expr(cp.percent))
            is ChildRr -> ChildRr(exprs.expr(cp.multiplier))
            is ChildArmedTrail -> ChildArmedTrail(exprs.expr(cp.trailDistance), exprs.expr(cp.mfeThreshold))
        }

    fun bracket(b: BracketAst): BracketAst = BracketAst(b.stopLoss?.let(::childPrice), b.takeProfit?.let(::childPrice))

    fun oco(o: OcoAst): OcoAst = OcoAst(childPrice(o.stop), childPrice(o.limit))
}
