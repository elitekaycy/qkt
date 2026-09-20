package com.qkt.dsl.parse

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Limit
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

/*
 * Iteration-variable substitution for the order-shaped parts of an action: sizing, order
 * type, time in force, bracket and OCO child prices, and stack specs. See [substituteIterVar].
 */
internal fun subst(
    s: SizingAst,
    v: String,
    alias: String,
): SizingAst =
    when (s) {
        is SizeQty -> s.copy(expr = subst(s.expr, v, alias))
        is SizeNotional -> s.copy(usd = subst(s.usd, v, alias))
        is SizePctEquity -> s.copy(frac = subst(s.frac, v, alias))
        is SizePctBalance -> s.copy(frac = subst(s.frac, v, alias))
        is SizeRiskFrac -> s.copy(frac = subst(s.frac, v, alias))
        is SizeRiskFracOfBook -> s.copy(frac = subst(s.frac, v, alias))
        is SizeRiskAbs -> s.copy(usd = subst(s.usd, v, alias))
        is SizePositionFull -> if (s.stream == v) SizePositionFull(alias) else s
    }

internal fun subst(
    o: OrderTypeAst,
    v: String,
    alias: String,
): OrderTypeAst =
    when (o) {
        is com.qkt.dsl.ast.Market -> o
        is Limit -> o.copy(price = subst(o.price, v, alias))
        is com.qkt.dsl.ast.ExitRelativeLimit ->
            o.copy(price = o.price.copy(dist = subst(o.price.dist, v, alias)))
        is Stop -> o.copy(price = subst(o.price, v, alias))
        is com.qkt.dsl.ast.ExitRelativeStop ->
            o.copy(price = o.price.copy(dist = subst(o.price.dist, v, alias)))
        is StopLimit ->
            o.copy(
                stopPrice = subst(o.stopPrice, v, alias),
                limitPrice = subst(o.limitPrice, v, alias),
            )
        is TrailingBy -> o.copy(distance = subst(o.distance, v, alias))
        is TrailingPct -> o.copy(percent = subst(o.percent, v, alias))
    }

internal fun subst(
    t: TifAst,
    v: String,
    alias: String,
): TifAst =
    when (t) {
        is Gtd -> t.copy(until = subst(t.until, v, alias))
        else -> t
    }

internal fun subst(
    cp: ChildPriceAst,
    v: String,
    alias: String,
): ChildPriceAst =
    when (cp) {
        is ChildAt -> cp.copy(price = subst(cp.price, v, alias))
        is ChildBy -> cp.copy(distance = subst(cp.distance, v, alias))
        is ChildPct -> cp.copy(percent = subst(cp.percent, v, alias))
        is ChildRr -> cp.copy(multiplier = subst(cp.multiplier, v, alias))
        is ChildArmedTrail ->
            cp.copy(
                trailDistance = subst(cp.trailDistance, v, alias),
                mfeThreshold = subst(cp.mfeThreshold, v, alias),
            )
    }

internal fun subst(
    b: BracketAst,
    v: String,
    alias: String,
): BracketAst =
    BracketAst(
        stopLoss = b.stopLoss?.let { subst(it, v, alias) },
        takeProfit = b.takeProfit?.let { subst(it, v, alias) },
    )

internal fun subst(
    o: OcoAst,
    v: String,
    alias: String,
): OcoAst = OcoAst(stop = subst(o.stop, v, alias), limit = subst(o.limit, v, alias))

internal fun subst(
    s: StackAst,
    v: String,
    alias: String,
): StackAst =
    when (s) {
        is StackSpacing -> s.copy(spacing = subst(s.spacing, v, alias))
        is StackLayers -> s.copy(layers = s.layers.map { subst(it, v, alias) })
    }

internal fun subst(
    layer: StackLayer,
    v: String,
    alias: String,
): StackLayer =
    layer.copy(
        sizing = subst(layer.sizing, v, alias),
        orderType = layer.orderType?.let { subst(it, v, alias) },
        at = layer.at?.let { subst(it, v, alias) },
    )

internal fun subst(
    c: StackAtClause,
    v: String,
    alias: String,
): StackAtClause =
    c.copy(
        mfeThreshold = subst(c.mfeThreshold, v, alias),
        sizing = subst(c.sizing, v, alias),
        bracket = subst(c.bracket, v, alias),
        maeRecoverDistance = c.maeRecoverDistance?.let { subst(it, v, alias) },
    )
