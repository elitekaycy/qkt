package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct

/**
 * Calls [visit] on every expression an action carries — sizing, repeat counts, order-type and
 * GTD prices, bracket/OCO child prices, stack specs, ON_FILL children and exit hooks — so
 * analyses such as [WarmupRequirements] see action-side indicators, not just conditions.
 */
internal fun visitActionExprs(
    action: ActionAst,
    visit: (ExprAst) -> Unit,
) {
    when (action) {
        is Buy -> visitOptsExprs(action.opts, visit)
        is Sell -> visitOptsExprs(action.opts, visit)
        is Block -> action.actions.forEach { visitActionExprs(it, visit) }
        is OcoEntry -> {
            visitActionExprs(action.leg1, visit)
            visitActionExprs(action.leg2, visit)
        }
        is com.qkt.dsl.ast.Resize -> {
            visitSizingExprs(action.target, visit)
            action.minStep?.let { visit(it) }
        }
        else -> Unit
    }
}

private fun visitOptsExprs(
    opts: ActionOpts,
    visit: (ExprAst) -> Unit,
) {
    opts.sizing?.let { visitSizingExprs(it, visit) }
    opts.times?.let { visit(it) }
    opts.orderType?.let { visitOrderTypeExprs(it, visit) }
    opts.tif?.let { if (it is Gtd) visit(it.until) }
    opts.bracket?.stopLoss?.let { visitChildPriceExprs(it, visit) }
    opts.bracket?.takeProfit?.let { visitChildPriceExprs(it, visit) }
    opts.oco?.let {
        visitChildPriceExprs(it.stop, visit)
        visitChildPriceExprs(it.limit, visit)
    }
    when (val stack = opts.stack) {
        is StackSpacing -> visit(stack.spacing)
        is StackLayers ->
            for (layer in stack.layers) {
                visitSizingExprs(layer.sizing, visit)
                layer.orderType?.let { visitOrderTypeExprs(it, visit) }
                layer.at?.let { visit(it) }
            }
        null -> Unit
    }
    for (tier in opts.stackAts) {
        visit(tier.mfeThreshold)
        tier.maeRecoverDistance?.let { visit(it) }
        visitSizingExprs(tier.sizing, visit)
        tier.bracket.stopLoss?.let { visitChildPriceExprs(it, visit) }
        tier.bracket.takeProfit?.let { visitChildPriceExprs(it, visit) }
    }
    // OTO (ON_FILL) children warm the gate too — an indicator in a child's price computes
    // garbage on a half-warm window exactly like one in the parent.
    opts.onFill.forEach { visitActionExprs(it, visit) }
    (opts.exitHooks.onStop + opts.exitHooks.onTakeProfit + opts.exitHooks.onClose)
        .forEach { visitActionExprs(it, visit) }
}

private fun visitSizingExprs(
    sizing: SizingAst,
    visit: (ExprAst) -> Unit,
) {
    when (sizing) {
        is SizeQty -> visit(sizing.expr)
        is SizeNotional -> visit(sizing.usd)
        is SizeRiskAbs -> visit(sizing.usd)
        is SizeRiskFrac -> visit(sizing.frac)
        is SizeRiskFracOfBook -> visit(sizing.frac)
        is SizePctEquity -> visit(sizing.frac)
        is SizePctBalance -> visit(sizing.frac)
        else -> Unit
    }
}

private fun visitOrderTypeExprs(
    ot: OrderTypeAst,
    visit: (ExprAst) -> Unit,
) {
    when (ot) {
        is Limit -> visit(ot.price)
        is Stop -> visit(ot.price)
        is StopLimit -> {
            visit(ot.stopPrice)
            visit(ot.limitPrice)
        }
        is TrailingBy -> visit(ot.distance)
        is TrailingPct -> visit(ot.percent)
        else -> Unit
    }
}

private fun visitChildPriceExprs(
    cp: ChildPriceAst,
    visit: (ExprAst) -> Unit,
) {
    when (cp) {
        is ChildAt -> visit(cp.price)
        is ChildBy -> visit(cp.distance)
        is ChildPct -> visit(cp.percent)
        is ChildRr -> visit(cp.multiplier)
        is ChildArmedTrail -> {
            visit(cp.trailDistance)
            visit(cp.mfeThreshold)
        }
    }
}
