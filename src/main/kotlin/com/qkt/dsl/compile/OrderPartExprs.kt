package com.qkt.dsl.compile

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
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
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct

/**
 * Walks the order-shaped parts of an action — sizing, order type, TIF, bracket and OCO child
 * prices, stack specs — handing every expression found to `visit`. A full-position size carries
 * a stream rather than an expression and goes to `onPositionStream`. Shared by
 * [collectStreamAliases] and [collectMetaRefs].
 */
internal object OrderPartExprs {
    fun sizing(
        s: SizingAst?,
        visit: (ExprAst) -> Unit,
        onPositionStream: (String) -> Unit = {},
    ) {
        when (s) {
            null -> Unit
            is SizeQty -> visit(s.expr)
            is SizeNotional -> visit(s.usd)
            is SizePctEquity -> visit(s.frac)
            is SizePctBalance -> visit(s.frac)
            is SizeRiskFrac -> visit(s.frac)
            is SizeRiskFracOfBook -> visit(s.frac)
            is SizeRiskAbs -> visit(s.usd)
            is SizePositionFull -> onPositionStream(s.stream)
        }
    }

    fun orderType(
        o: OrderTypeAst?,
        visit: (ExprAst) -> Unit,
    ) {
        when (o) {
            null, Market -> Unit
            is Limit -> visit(o.price)
            is com.qkt.dsl.ast.ExitRelativeLimit -> visit(o.price.dist)
            is Stop -> visit(o.price)
            is com.qkt.dsl.ast.ExitRelativeStop -> visit(o.price.dist)
            is StopLimit -> {
                visit(o.stopPrice)
                visit(o.limitPrice)
            }
            is TrailingBy -> visit(o.distance)
            is TrailingPct -> visit(o.percent)
        }
    }

    fun childPrice(
        c: ChildPriceAst?,
        visit: (ExprAst) -> Unit,
    ) {
        when (c) {
            null -> Unit
            is ChildAt -> visit(c.price)
            is ChildBy -> visit(c.distance)
            is ChildPct -> visit(c.percent)
            is ChildRr -> visit(c.multiplier)
            is ChildArmedTrail -> {
                visit(c.trailDistance)
                visit(c.mfeThreshold)
            }
        }
    }

    fun bracket(
        b: BracketAst?,
        visit: (ExprAst) -> Unit,
    ) {
        if (b == null) return
        childPrice(b.stopLoss, visit)
        childPrice(b.takeProfit, visit)
    }

    fun oco(
        o: OcoAst?,
        visit: (ExprAst) -> Unit,
    ) {
        if (o == null) return
        childPrice(o.stop, visit)
        childPrice(o.limit, visit)
    }

    fun tif(
        t: TifAst?,
        visit: (ExprAst) -> Unit,
    ) {
        when (t) {
            null, Gtc, Ioc, Fok, Day -> Unit
            is Gtd -> visit(t.until)
        }
    }

    fun stack(
        s: StackAst?,
        visit: (ExprAst) -> Unit,
        onPositionStream: (String) -> Unit = {},
    ) {
        when (s) {
            null -> Unit
            is StackSpacing -> visit(s.spacing)
            is StackLayers ->
                s.layers.forEach { layer ->
                    sizing(layer.sizing, visit, onPositionStream)
                    orderType(layer.orderType, visit)
                    layer.at?.let { visit(it) }
                }
        }
    }
}
