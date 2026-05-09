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
    ): StackPlan =
        when (ast) {
            is StackSpacing -> compileSpacing(ast, outerSizing, outerBracket)
            is StackLayers -> compileLayers(ast, outerBracket)
        }

    private fun compileSpacing(
        ast: StackSpacing,
        outerSizing: SizingAst?,
        outerBracket: BracketAst?,
    ): StackPlan {
        val sizing =
            outerSizing
                ?: error("STACK SPACING form requires outer SIZING on the BUY/SELL action")
        val layers =
            (1..ast.count).map { i ->
                val trigger =
                    if (i == 1) {
                        Immediate
                    } else {
                        val offset = scaleOffset(ast.spacing, i - 1)
                        At(BinaryOp(BinOp.ADD, StackEntryRef, offset), ast.direction)
                    }
                LayerSpec(
                    index = i,
                    sizing = sizing,
                    orderType = Market,
                    trigger = trigger,
                )
            }
        return StackPlan(layers, outerBracket, ast.within?.millis)
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
    ): StackPlan {
        val layers =
            ast.layers.mapIndexed { idx, l ->
                val trigger =
                    if (l.at == null) {
                        require(idx == 0) { "layer ${idx + 1} must have AT" }
                        Immediate
                    } else {
                        At(l.at, com.qkt.dsl.ast.StackDirection.TRADE_DIRECTION)
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
}
