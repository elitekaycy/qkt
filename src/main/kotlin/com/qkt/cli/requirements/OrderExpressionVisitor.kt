package com.qkt.cli.requirements

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct

/** Visits every expression inside an order's sizing, price, time-in-force, bracket, OCO and stack clauses. */
internal class OrderExpressionVisitor(
    private val walk: (ExprAst?) -> Unit,
) {
    /** Visits the expressions of a sizing clause. */
    fun walkSizing(sizing: SizingAst?) {
        when (sizing) {
            is SizeQty -> walk(sizing.expr)
            is SizeNotional -> walk(sizing.usd)
            is SizePctEquity -> walk(sizing.frac)
            is SizePctBalance -> walk(sizing.frac)
            is SizeRiskFrac -> walk(sizing.frac)
            is SizeRiskFracOfBook -> walk(sizing.frac)
            is SizeRiskAbs -> walk(sizing.usd)
            is SizePositionFull, null -> Unit
        }
    }

    /** Visits the price expressions of an order type. */
    fun walkOrder(orderType: OrderTypeAst?) {
        when (orderType) {
            is Limit -> walk(orderType.price)
            is com.qkt.dsl.ast.ExitRelativeLimit -> walk(orderType.price.dist)
            is Stop -> walk(orderType.price)
            is com.qkt.dsl.ast.ExitRelativeStop -> walk(orderType.price.dist)
            is StopLimit -> {
                walk(orderType.stopPrice)
                walk(orderType.limitPrice)
            }
            is TrailingBy -> walk(orderType.distance)
            is TrailingPct -> walk(orderType.percent)
            Market, null -> Unit
        }
    }

    /** Visits a GTD expiry expression. */
    fun walkTif(tif: TifAst?) {
        if (tif is Gtd) walk(tif.until)
    }

    /** Visits the price expressions of a bracket or OCO child. */
    fun walkChild(price: ChildPriceAst?) {
        when (price) {
            is ChildAt -> walk(price.price)
            is ChildBy -> walk(price.distance)
            is ChildPct -> walk(price.percent)
            is ChildRr -> walk(price.multiplier)
            is ChildArmedTrail -> {
                walk(price.trailDistance)
                walk(price.mfeThreshold)
            }
            null -> Unit
        }
    }

    /** Visits both children of a bracket. */
    fun walkBracket(bracket: BracketAst?) {
        if (bracket == null) return
        walkChild(bracket.stopLoss)
        walkChild(bracket.takeProfit)
    }

    /** Visits both legs of an OCO. */
    fun walkOco(oco: OcoAst?) {
        if (oco == null) return
        walkChild(oco.stop)
        walkChild(oco.limit)
    }

    /** Visits one stack layer's sizing, order and trigger. */
    fun walkLayer(layer: StackLayer) {
        walkSizing(layer.sizing)
        walkOrder(layer.orderType)
        walk(layer.at)
    }

    /** Visits a stack's spacing or its layers. */
    fun walkStack(stack: StackAst?) {
        when (stack) {
            is StackSpacing -> walk(stack.spacing)
            is StackLayers -> stack.layers.forEach(::walkLayer)
            null -> Unit
        }
    }

    /** Visits a `stack at` clause's thresholds, sizing and bracket. */
    fun walkStackAt(clause: StackAtClause) {
        walk(clause.mfeThreshold)
        clause.maeRecoverDistance?.let { walk(it) }
        walkSizing(clause.sizing)
        walkBracket(clause.bracket)
    }
}
