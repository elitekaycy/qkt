package com.qkt.dsl.parse

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchOrder
import com.qkt.dsl.ast.LatchSensor
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen

internal fun substituteIterVar(
    rule: WhenThen,
    iterVar: String,
    alias: String,
): WhenThen =
    WhenThen(
        cond = subst(rule.cond, iterVar, alias),
        action = subst(rule.action, iterVar, alias),
    )

private fun subst(
    expr: ExprAst,
    v: String,
    alias: String,
): ExprAst =
    when (expr) {
        is Ref -> if (expr.name == v) Ref(alias, expr.snapshot) else expr
        is StreamFieldRef -> if (expr.stream == v) StreamFieldRef(alias, expr.field) else expr
        is PositionRef -> if (expr.stream == v) PositionRef(alias) else expr
        is StateAccessor -> if (expr.key == v) StateAccessor(expr.source, alias) else expr
        is BinaryOp -> expr.copy(lhs = subst(expr.lhs, v, alias), rhs = subst(expr.rhs, v, alias))
        is UnaryOp -> expr.copy(arg = subst(expr.arg, v, alias))
        is CmpOp -> expr.copy(lhs = subst(expr.lhs, v, alias), rhs = subst(expr.rhs, v, alias))
        is IndicatorCall -> expr.copy(args = expr.args.map { subst(it, v, alias) })
        is FuncCall -> expr.copy(args = expr.args.map { subst(it, v, alias) })
        is Between ->
            expr.copy(
                v = subst(expr.v, v, alias),
                lo = subst(expr.lo, v, alias),
                hi = subst(expr.hi, v, alias),
            )
        is InList ->
            expr.copy(
                v = subst(expr.v, v, alias),
                members = expr.members.map { subst(it, v, alias) },
            )
        is Crosses -> expr.copy(lhs = subst(expr.lhs, v, alias), rhs = subst(expr.rhs, v, alias))
        is CaseWhen ->
            expr.copy(
                branches = expr.branches.map { (c, e) -> subst(c, v, alias) to subst(e, v, alias) },
                elseExpr = subst(expr.elseExpr, v, alias),
            )
        is Aggregate -> expr.copy(series = subst(expr.series, v, alias))
        is IsNull -> expr.copy(expr = subst(expr.expr, v, alias))
        else -> expr
    }

private fun subst(
    action: ActionAst,
    v: String,
    alias: String,
): ActionAst =
    when (action) {
        is Buy -> Buy(if (action.stream == v) alias else action.stream, subst(action.opts, v, alias))
        is Sell -> Sell(if (action.stream == v) alias else action.stream, subst(action.opts, v, alias))
        is Close -> if (action.stream == v) Close(alias) else action
        is Cancel -> if (action.stream == v) Cancel(alias) else action
        is Block -> Block(action.actions.map { subst(it, v, alias) })
        is OcoEntry -> OcoEntry(subst(action.leg1, v, alias), subst(action.leg2, v, alias))
        is Latch ->
            action.copy(
                stream = if (action.stream == v) alias else action.stream,
                sensor = subst(action.sensor, v, alias),
                entries = action.entries.map { subst(it, v, alias) },
            )
        is com.qkt.dsl.ast.Resize ->
            action.copy(
                stream = if (action.stream == v) alias else action.stream,
                target = subst(action.target, v, alias),
                minStep = action.minStep?.let { subst(it, v, alias) },
            )
        else -> action
    }

private fun subst(
    opts: ActionOpts,
    v: String,
    alias: String,
): ActionOpts =
    opts.copy(
        sizing = opts.sizing?.let { subst(it, v, alias) },
        orderType = opts.orderType?.let { subst(it, v, alias) },
        tif = opts.tif?.let { subst(it, v, alias) },
        bracket = opts.bracket?.let { subst(it, v, alias) },
        oco = opts.oco?.let { subst(it, v, alias) },
        stack = opts.stack?.let { subst(it, v, alias) },
        stackAts = opts.stackAts.map { subst(it, v, alias) },
    )

private fun subst(
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
        is SizeRiskAbs -> s.copy(usd = subst(s.usd, v, alias))
        is SizePositionFull -> if (s.stream == v) SizePositionFull(alias) else s
    }

private fun subst(
    o: OrderTypeAst,
    v: String,
    alias: String,
): OrderTypeAst =
    when (o) {
        is com.qkt.dsl.ast.Market -> o
        is Limit -> o.copy(price = subst(o.price, v, alias))
        is Stop -> o.copy(price = subst(o.price, v, alias))
        is StopLimit ->
            o.copy(
                stopPrice = subst(o.stopPrice, v, alias),
                limitPrice = subst(o.limitPrice, v, alias),
            )
        is TrailingBy -> o.copy(distance = subst(o.distance, v, alias))
        is TrailingPct -> o.copy(frac = subst(o.frac, v, alias))
    }

private fun subst(
    t: TifAst,
    v: String,
    alias: String,
): TifAst =
    when (t) {
        is Gtd -> t.copy(until = subst(t.until, v, alias))
        else -> t
    }

private fun subst(
    cp: ChildPriceAst,
    v: String,
    alias: String,
): ChildPriceAst =
    when (cp) {
        is ChildAt -> cp.copy(price = subst(cp.price, v, alias))
        is ChildBy -> cp.copy(distance = subst(cp.distance, v, alias))
        is ChildPct -> cp.copy(frac = subst(cp.frac, v, alias))
        is ChildRr -> cp.copy(multiplier = subst(cp.multiplier, v, alias))
        is ChildArmedTrail ->
            cp.copy(
                trailDistance = subst(cp.trailDistance, v, alias),
                mfeThreshold = subst(cp.mfeThreshold, v, alias),
            )
    }

private fun subst(
    b: BracketAst,
    v: String,
    alias: String,
): BracketAst =
    BracketAst(
        stopLoss = b.stopLoss?.let { subst(it, v, alias) },
        takeProfit = b.takeProfit?.let { subst(it, v, alias) },
    )

private fun subst(
    o: OcoAst,
    v: String,
    alias: String,
): OcoAst = OcoAst(stop = subst(o.stop, v, alias), limit = subst(o.limit, v, alias))

private fun subst(
    s: StackAst,
    v: String,
    alias: String,
): StackAst =
    when (s) {
        is StackSpacing -> s.copy(spacing = subst(s.spacing, v, alias))
        is StackLayers -> s.copy(layers = s.layers.map { subst(it, v, alias) })
    }

private fun subst(
    layer: StackLayer,
    v: String,
    alias: String,
): StackLayer =
    layer.copy(
        sizing = subst(layer.sizing, v, alias),
        orderType = layer.orderType?.let { subst(it, v, alias) },
        at = layer.at?.let { subst(it, v, alias) },
    )

private fun subst(
    c: StackAtClause,
    v: String,
    alias: String,
): StackAtClause =
    c.copy(
        mfeThreshold = subst(c.mfeThreshold, v, alias),
        sizing = subst(c.sizing, v, alias),
        bracket = subst(c.bracket, v, alias),
    )

private fun subst(
    s: LatchSensor,
    v: String,
    alias: String,
): LatchSensor =
    when (s) {
        is BreakOffset ->
            s.copy(
                reference = s.reference?.let { subst(it, v, alias) },
                offset = subst(s.offset, v, alias),
            )
    }

private fun subst(
    e: LatchEntry,
    v: String,
    alias: String,
): LatchEntry =
    e.copy(
        order = subst(e.order, v, alias),
        bracket = e.bracket?.let { subst(it, v, alias) },
        sizing = e.sizing?.let { subst(it, v, alias) },
    )

private fun subst(
    o: LatchOrder,
    v: String,
    alias: String,
): LatchOrder =
    when (o) {
        is LatchMarket -> o
        is LatchLimit -> o.copy(price = subst(o.price, v, alias))
        is LatchStop -> o.copy(price = subst(o.price, v, alias))
    }

private fun subst(
    rel: DirRel,
    v: String,
    alias: String,
): DirRel = rel.copy(dist = subst(rel.dist, v, alias))

private fun subst(
    b: LatchBracket,
    v: String,
    alias: String,
): LatchBracket =
    b.copy(
        stopLoss = b.stopLoss?.let { subst(it, v, alias) },
        takeProfit = b.takeProfit?.let { subst(it, v, alias) },
    )
