package com.qkt.cli.requirements

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.LastTradingDayOfMonth
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SessionWindow
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.stdlib.IndicatorRegistry

/**
 * Walks strategy expressions and records which stream aliases they read quotes (`bid`, `ask`,
 * `spread`) or volume from, including the source series of volume-requiring indicators.
 */
internal class ExprDataRequirementCollector {
    private val quoteFields = setOf("bid", "ask", "spread")

    /** Aliases whose bid/ask/spread the strategy reads, in first-seen order. */
    val quoteAliases = mutableSetOf<String>()

    /** Aliases whose volume the strategy reads, in first-seen order. */
    val volumeAliases = mutableSetOf<String>()

    /** Records the quote and volume stream aliases [expr] reads, recursing into sub-expressions. */
    fun walk(expr: ExprAst?) {
        when (expr) {
            is StreamFieldRef -> {
                if (expr.field in quoteFields) quoteAliases.add(expr.stream)
                if (expr.field == "volume") volumeAliases.add(expr.stream)
            }
            is IndicatorCall -> {
                if (IndicatorRegistry.spec(expr.name)?.requiresVolume == true) {
                    val aliases = mutableSetOf<String>()
                    collectAliases(expr.args.firstOrNull(), aliases)
                    if (aliases.isEmpty()) expr.args.forEach { collectAliases(it, aliases) }
                    volumeAliases.addAll(aliases)
                }
                expr.args.forEach(::walk)
            }
            is BinaryOp -> {
                walk(expr.lhs)
                walk(expr.rhs)
            }
            is UnaryOp -> walk(expr.arg)
            is CmpOp -> {
                walk(expr.lhs)
                walk(expr.rhs)
            }
            is Crosses -> {
                walk(expr.lhs)
                walk(expr.rhs)
            }
            is FuncCall -> expr.args.forEach(::walk)
            is Aggregate -> walk(expr.series)
            is Between -> {
                walk(expr.v)
                walk(expr.lo)
                walk(expr.hi)
            }
            is InList -> {
                walk(expr.v)
                expr.members.forEach(::walk)
            }
            is CaseWhen -> {
                expr.branches.forEach { (condition, value) ->
                    walk(condition)
                    walk(value)
                }
                walk(expr.elseExpr)
            }
            is IsNull -> walk(expr.expr)
            is AccountRef,
            is BoolLit,
            is CalendarWindow,
            EntryQty,
            LastTradingDayOfMonth,
            is NowAccessor,
            is NumLit,
            is PositionRef,
            is Ref,
            is SequenceAccessor,
            is SessionWindow,
            StackEntryRef,
            is StateAccessor,
            is StreakRef,
            is TradesRef,
            is CooldownRef,
            is StringLit,
            is com.qkt.dsl.ast.ExitRef,
            null,
            -> Unit
        }
    }

    fun collectAliases(
        expr: ExprAst?,
        out: MutableSet<String>,
    ) {
        when (expr) {
            is StreamFieldRef -> out.add(expr.stream)
            is BinaryOp -> {
                collectAliases(expr.lhs, out)
                collectAliases(expr.rhs, out)
            }
            is UnaryOp -> collectAliases(expr.arg, out)
            is CmpOp -> {
                collectAliases(expr.lhs, out)
                collectAliases(expr.rhs, out)
            }
            is Crosses -> {
                collectAliases(expr.lhs, out)
                collectAliases(expr.rhs, out)
            }
            is FuncCall -> expr.args.forEach { collectAliases(it, out) }
            is IndicatorCall -> expr.args.forEach { collectAliases(it, out) }
            is Aggregate -> collectAliases(expr.series, out)
            is Between -> {
                collectAliases(expr.v, out)
                collectAliases(expr.lo, out)
                collectAliases(expr.hi, out)
            }
            is InList -> {
                collectAliases(expr.v, out)
                expr.members.forEach { collectAliases(it, out) }
            }
            is CaseWhen -> {
                expr.branches.forEach { (condition, value) ->
                    collectAliases(condition, out)
                    collectAliases(value, out)
                }
                collectAliases(expr.elseExpr, out)
            }
            is IsNull -> collectAliases(expr.expr, out)
            else -> Unit
        }
    }
}
