package com.qkt.dsl.parse

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.TradesRef
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

internal fun subst(
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
        is StreakRef -> expr
        is TradesRef -> expr
        is CooldownRef -> expr
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
                confirm = subst(action.confirm, v, alias),
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
        onFill = opts.onFill.map { subst(it, v, alias) },
        times = opts.times?.let { subst(it, v, alias) },
        exitHooks =
            opts.exitHooks.copy(
                onStop = opts.exitHooks.onStop.map { subst(it, v, alias) },
                onTakeProfit = opts.exitHooks.onTakeProfit.map { subst(it, v, alias) },
                onClose = opts.exitHooks.onClose.map { subst(it, v, alias) },
            ),
    )
