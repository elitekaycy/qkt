package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.StackPlan

object StackCompiler {
    fun compile(
        ast: StackAst,
        outerSizing: SizingAst?,
        outerBracket: BracketAst?,
        side: com.qkt.common.Side,
    ): StackPlan =
        when (ast) {
            is StackSpacing -> compileSpacing(ast, outerSizing, outerBracket, side)
            is StackLayers -> compileLayers(ast, outerBracket, side)
        }

    private fun compileSpacing(
        ast: StackSpacing,
        outerSizing: SizingAst?,
        outerBracket: BracketAst?,
        side: com.qkt.common.Side,
    ): StackPlan {
        val sizing =
            outerSizing
                ?: error("STACK SPACING form requires outer SIZING on the BUY/SELL action")
        val effectiveDirection = resolveDirection(ast.direction, side)
        val layers =
            (1..ast.count).map { i ->
                if (i == 1) {
                    LayerSpec(
                        index = 1,
                        sizing = sizing,
                        orderType = Market,
                        trigger = Immediate,
                    )
                } else {
                    val offset = scaleOffset(ast.spacing, i - 1)
                    val triggerExpr =
                        if (effectiveDirection == com.qkt.dsl.ast.StackDirection.BELOW) {
                            BinaryOp(BinOp.SUB, StackEntryRef, offset)
                        } else {
                            BinaryOp(BinOp.ADD, StackEntryRef, offset)
                        }
                    val orderType =
                        if (isStopLayer(side, effectiveDirection)) {
                            Market
                        } else {
                            com.qkt.dsl.ast
                                .Limit(triggerExpr)
                        }
                    LayerSpec(
                        index = i,
                        sizing = sizing,
                        orderType = orderType,
                        trigger = At(triggerExpr, effectiveDirection),
                    )
                }
            }
        return StackPlan(layers, outerBracket, ast.within?.millis)
    }

    /**
     * Whether a triggered layer should be a Stop or a Limit. Stop fires when price moves
     * THROUGH the trigger in the trade direction; Limit fills when price reaches the trigger
     * from the opposite side.
     */
    private fun isStopLayer(
        side: com.qkt.common.Side,
        direction: com.qkt.dsl.ast.StackDirection,
    ): Boolean =
        when (direction) {
            com.qkt.dsl.ast.StackDirection.ABOVE -> side == com.qkt.common.Side.BUY
            com.qkt.dsl.ast.StackDirection.BELOW -> side == com.qkt.common.Side.SELL
            com.qkt.dsl.ast.StackDirection.TRADE_DIRECTION ->
                error("direction must be resolved before isStopLayer")
        }

    private fun scaleOffset(
        spacing: com.qkt.dsl.ast.ExprAst,
        multiplier: Int,
    ): com.qkt.dsl.ast.ExprAst =
        if (spacing is NumLit) {
            NumLit(spacing.value.multiply(java.math.BigDecimal(multiplier)))
        } else {
            BinaryOp(BinOp.MUL, spacing, NumLit(java.math.BigDecimal(multiplier)))
        }

    private fun compileLayers(
        ast: StackLayers,
        outerBracket: BracketAst?,
        side: com.qkt.common.Side,
    ): StackPlan {
        val effectiveDirection =
            if (side == com.qkt.common.Side.BUY) {
                com.qkt.dsl.ast.StackDirection.ABOVE
            } else {
                com.qkt.dsl.ast.StackDirection.BELOW
            }
        val layers =
            ast.layers.mapIndexed { idx, l ->
                val trigger =
                    if (l.at == null) {
                        require(idx == 0) { "layer ${idx + 1} must have AT" }
                        Immediate
                    } else {
                        At(l.at, effectiveDirection)
                    }
                LayerSpec(
                    index = idx + 1,
                    sizing = l.sizing,
                    orderType = l.orderType ?: Market,
                    trigger = trigger,
                )
            }
        return StackPlan(layers, outerBracket, ast.within?.millis)
    }

    private fun resolveDirection(
        direction: com.qkt.dsl.ast.StackDirection,
        side: com.qkt.common.Side,
    ): com.qkt.dsl.ast.StackDirection =
        when (direction) {
            com.qkt.dsl.ast.StackDirection.ABOVE -> com.qkt.dsl.ast.StackDirection.ABOVE
            com.qkt.dsl.ast.StackDirection.BELOW -> com.qkt.dsl.ast.StackDirection.BELOW
            com.qkt.dsl.ast.StackDirection.TRADE_DIRECTION ->
                if (side == com.qkt.common.Side.BUY) {
                    com.qkt.dsl.ast.StackDirection.ABOVE
                } else {
                    com.qkt.dsl.ast.StackDirection.BELOW
                }
        }
}
