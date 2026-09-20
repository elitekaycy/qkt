package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst

/** Aliases whose conditions read `bid`/`ask`/`spread` — see [DslCompiledStrategy.quoteFieldStreams]. */
internal fun collectQuoteFieldStreams(conditions: List<ExprAst>): Set<String> {
    val out = mutableSetOf<String>()

    fun walk(e: ExprAst) {
        when (e) {
            is com.qkt.dsl.ast.StreamFieldRef ->
                if (e.field in setOf("bid", "ask", "spread")) out.add(e.stream)
            is com.qkt.dsl.ast.BinaryOp -> {
                walk(e.lhs)
                walk(e.rhs)
            }
            is com.qkt.dsl.ast.UnaryOp -> walk(e.arg)
            is com.qkt.dsl.ast.CmpOp -> {
                walk(e.lhs)
                walk(e.rhs)
            }
            is com.qkt.dsl.ast.Crosses -> {
                walk(e.lhs)
                walk(e.rhs)
            }
            is com.qkt.dsl.ast.FuncCall -> e.args.forEach(::walk)
            is com.qkt.dsl.ast.IndicatorCall -> e.args.forEach(::walk)
            is com.qkt.dsl.ast.Aggregate -> walk(e.series)
            is com.qkt.dsl.ast.Between -> {
                walk(e.v)
                walk(e.lo)
                walk(e.hi)
            }
            is com.qkt.dsl.ast.InList -> {
                walk(e.v)
                e.members.forEach(::walk)
            }
            is com.qkt.dsl.ast.CaseWhen -> {
                e.branches.forEach { (c, b) ->
                    walk(c)
                    walk(b)
                }
                walk(e.elseExpr)
            }
            is com.qkt.dsl.ast.IsNull -> walk(e.expr)
            is com.qkt.dsl.ast.NumLit,
            is com.qkt.dsl.ast.BoolLit,
            is com.qkt.dsl.ast.StringLit,
            is com.qkt.dsl.ast.Ref,
            is com.qkt.dsl.ast.NowAccessor,
            is com.qkt.dsl.ast.CalendarWindow,
            is com.qkt.dsl.ast.SessionWindow,
            com.qkt.dsl.ast.LastTradingDayOfMonth,
            is com.qkt.dsl.ast.AccountRef,
            is com.qkt.dsl.ast.StreakRef,
            is com.qkt.dsl.ast.TradesRef,
            is com.qkt.dsl.ast.CooldownRef,
            is com.qkt.dsl.ast.PositionRef,
            is com.qkt.dsl.ast.StateAccessor,
            com.qkt.dsl.ast.StackEntryRef,
            com.qkt.dsl.ast.EntryQty,
            is com.qkt.dsl.ast.ExitRef,
            is com.qkt.dsl.ast.SequenceAccessor,
            -> Unit
        }
    }
    conditions.forEach(::walk)
    return out
}
