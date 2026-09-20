package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst

/** Rejects `a < b < c`: a comparison whose operand is itself a comparison. */
internal fun rejectChainedComparisons(expr: ExprAst) {
    fun walk(current: ExprAst) {
        when (current) {
            is com.qkt.dsl.ast.CmpOp -> {
                require(current.lhs !is com.qkt.dsl.ast.CmpOp && current.rhs !is com.qkt.dsl.ast.CmpOp) {
                    "Chained comparisons are not supported; combine explicit comparisons with AND"
                }
                walk(current.lhs)
                walk(current.rhs)
            }
            is com.qkt.dsl.ast.BinaryOp -> {
                walk(current.lhs)
                walk(current.rhs)
            }
            is com.qkt.dsl.ast.UnaryOp -> walk(current.arg)
            is com.qkt.dsl.ast.Crosses -> {
                walk(current.lhs)
                walk(current.rhs)
            }
            is com.qkt.dsl.ast.FuncCall -> current.args.forEach(::walk)
            is com.qkt.dsl.ast.IndicatorCall -> current.args.forEach(::walk)
            is com.qkt.dsl.ast.Aggregate -> walk(current.series)
            is com.qkt.dsl.ast.Between -> {
                walk(current.v)
                walk(current.lo)
                walk(current.hi)
            }
            is com.qkt.dsl.ast.InList -> {
                walk(current.v)
                current.members.forEach(::walk)
            }
            is com.qkt.dsl.ast.CaseWhen -> {
                current.branches.forEach { (condition, branch) ->
                    walk(condition)
                    walk(branch)
                }
                walk(current.elseExpr)
            }
            is com.qkt.dsl.ast.IsNull -> walk(current.expr)
            is com.qkt.dsl.ast.NumLit,
            is com.qkt.dsl.ast.BoolLit,
            is com.qkt.dsl.ast.StringLit,
            is com.qkt.dsl.ast.Ref,
            is com.qkt.dsl.ast.StreamFieldRef,
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
    walk(expr)
}
