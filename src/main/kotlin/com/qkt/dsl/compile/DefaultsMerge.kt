package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.kotlin.SYMBOL_PLACEHOLDER_NAME

fun mergeDefaults(
    action: ActionAst,
    defaults: DefaultsBlock?,
): ActionAst {
    if (defaults == null) return action
    return when (action) {
        is Buy -> Buy(action.stream, mergeOpts(action.opts, defaults, action.stream))
        is Sell -> Sell(action.stream, mergeOpts(action.opts, defaults, action.stream))
        else -> action
    }
}

private fun mergeOpts(
    opts: ActionOpts,
    d: DefaultsBlock,
    alias: String,
): ActionOpts =
    ActionOpts(
        sizing = opts.sizing ?: d.sizing?.let { substituteSymbol(it, alias) },
        orderType =
            opts.orderType
                ?: (d.orderType ?: d.trailing)?.let { substituteSymbol(it, alias) },
        tif = opts.tif ?: d.tif?.let { substituteSymbol(it, alias) },
        bracket =
            mergeBracket(
                opts.bracket,
                d.stopLoss?.let { substituteSymbol(it, alias) },
                d.takeProfit?.let { substituteSymbol(it, alias) },
            ),
        oco = opts.oco,
    )

private fun mergeBracket(
    actionBracket: BracketAst?,
    defSL: ChildPriceAst?,
    defTP: ChildPriceAst?,
): BracketAst? {
    if (actionBracket == null) {
        if (defSL != null && defTP != null) return BracketAst(defSL, defTP)
        return null
    }
    return BracketAst(actionBracket.stopLoss ?: defSL, actionBracket.takeProfit ?: defTP)
}

private fun substituteSymbol(
    expr: ExprAst,
    alias: String,
): ExprAst =
    when (expr) {
        is Ref ->
            if (expr.name == SYMBOL_PLACEHOLDER_NAME) StreamFieldRef(alias, "candle") else expr
        is BinaryOp ->
            expr.copy(
                lhs = substituteSymbol(expr.lhs, alias),
                rhs = substituteSymbol(expr.rhs, alias),
            )
        is UnaryOp -> expr.copy(arg = substituteSymbol(expr.arg, alias))
        is CmpOp ->
            expr.copy(
                lhs = substituteSymbol(expr.lhs, alias),
                rhs = substituteSymbol(expr.rhs, alias),
            )
        is IndicatorCall -> expr.copy(args = expr.args.map { substituteSymbol(it, alias) })
        is FuncCall -> expr.copy(args = expr.args.map { substituteSymbol(it, alias) })
        is Between ->
            expr.copy(
                v = substituteSymbol(expr.v, alias),
                lo = substituteSymbol(expr.lo, alias),
                hi = substituteSymbol(expr.hi, alias),
            )
        is InList ->
            expr.copy(
                v = substituteSymbol(expr.v, alias),
                members = expr.members.map { substituteSymbol(it, alias) },
            )
        is Crosses ->
            expr.copy(
                lhs = substituteSymbol(expr.lhs, alias),
                rhs = substituteSymbol(expr.rhs, alias),
            )
        is CaseWhen ->
            expr.copy(
                branches = expr.branches.map { (c, e) -> substituteSymbol(c, alias) to substituteSymbol(e, alias) },
                elseExpr = substituteSymbol(expr.elseExpr, alias),
            )
        is Aggregate -> expr.copy(series = substituteSymbol(expr.series, alias))
        else -> expr
    }

private fun substituteSymbol(
    s: SizingAst,
    alias: String,
): SizingAst =
    when (s) {
        is SizeQty -> s.copy(expr = substituteSymbol(s.expr, alias))
        is SizeNotional -> s.copy(usd = substituteSymbol(s.usd, alias))
        is SizePctEquity -> s.copy(frac = substituteSymbol(s.frac, alias))
        is SizePctBalance -> s.copy(frac = substituteSymbol(s.frac, alias))
        is SizeRiskFrac -> s.copy(frac = substituteSymbol(s.frac, alias))
        is SizeRiskAbs -> s.copy(usd = substituteSymbol(s.usd, alias))
        else -> s
    }

private fun substituteSymbol(
    o: OrderTypeAst,
    alias: String,
): OrderTypeAst =
    when (o) {
        is Market -> o
        is Limit -> o.copy(price = substituteSymbol(o.price, alias))
        is Stop -> o.copy(price = substituteSymbol(o.price, alias))
        is StopLimit ->
            o.copy(
                stopPrice = substituteSymbol(o.stopPrice, alias),
                limitPrice = substituteSymbol(o.limitPrice, alias),
            )
        is TrailingBy -> o.copy(distance = substituteSymbol(o.distance, alias))
        is TrailingPct -> o.copy(frac = substituteSymbol(o.frac, alias))
    }

private fun substituteSymbol(
    t: TifAst,
    alias: String,
): TifAst =
    when (t) {
        is Gtd -> t.copy(until = substituteSymbol(t.until, alias))
        else -> t
    }

private fun substituteSymbol(
    cp: ChildPriceAst,
    alias: String,
): ChildPriceAst =
    when (cp) {
        is ChildAt -> cp.copy(price = substituteSymbol(cp.price, alias))
        is ChildBy -> cp.copy(distance = substituteSymbol(cp.distance, alias))
        is ChildPct -> cp.copy(frac = substituteSymbol(cp.frac, alias))
        is ChildRr -> cp.copy(multiplier = substituteSymbol(cp.multiplier, alias))
    }
