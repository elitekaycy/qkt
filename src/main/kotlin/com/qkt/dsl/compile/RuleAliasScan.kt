package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
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
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SessionWindow
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen

/**
 * Collect every stream alias the rule's condition or action references.
 *
 * Used by Phase 24's [WarmupGate] to decide whether a rule is allowed to fire.
 * A rule is gated by the union of aliases its condition expression, action target,
 * and any nested expressions (BRACKET, OCO, SIZING, STACK_AT) touch.
 *
 * Exhaustive over the sealed hierarchies - a new AST variant breaks the build here.
 */
fun collectStreamAliases(rule: WhenThen): Set<String> {
    val out = mutableSetOf<String>()

    fun walkExpr(e: ExprAst) {
        when (e) {
            is NumLit, is BoolLit, is StringLit -> Unit
            is Ref, is NowAccessor, is CalendarWindow, is SessionWindow,
            is AccountRef, is StateAccessor, is StreakRef, is TradesRef, is CooldownRef, StackEntryRef, EntryQty,
            is SequenceAccessor,
            LastTradingDayOfMonth, is com.qkt.dsl.ast.ExitRef,
            -> Unit
            is PositionRef -> out.add(e.stream)
            is StreamFieldRef -> out.add(e.stream)
            is IndicatorCall -> e.args.forEach { walkExpr(it) }
            is BinaryOp -> {
                walkExpr(e.lhs)
                walkExpr(e.rhs)
            }
            is UnaryOp -> walkExpr(e.arg)
            is CmpOp -> {
                walkExpr(e.lhs)
                walkExpr(e.rhs)
            }
            is Between -> {
                walkExpr(e.v)
                walkExpr(e.lo)
                walkExpr(e.hi)
            }
            is InList -> {
                walkExpr(e.v)
                e.members.forEach { walkExpr(it) }
            }
            is Crosses -> {
                walkExpr(e.lhs)
                walkExpr(e.rhs)
            }
            is CaseWhen -> {
                e.branches.forEach { (c, v) ->
                    walkExpr(c)
                    walkExpr(v)
                }
                walkExpr(e.elseExpr)
            }
            is Aggregate -> walkExpr(e.series)
            is FuncCall -> e.args.forEach { walkExpr(it) }
            is IsNull -> walkExpr(e.expr)
        }
    }

    fun walkOpts(opts: ActionOpts) {
        OrderPartExprs.sizing(opts.sizing, ::walkExpr) { out.add(it) }
        opts.times?.let { walkExpr(it) }
        OrderPartExprs.orderType(opts.orderType, ::walkExpr)
        OrderPartExprs.tif(opts.tif, ::walkExpr)
        OrderPartExprs.bracket(opts.bracket, ::walkExpr)
        OrderPartExprs.oco(opts.oco, ::walkExpr)
        OrderPartExprs.stack(opts.stack, ::walkExpr) { out.add(it) }
        opts.stackAts.forEach { clause ->
            walkExpr(clause.mfeThreshold)
            clause.maeRecoverDistance?.let { walkExpr(it) }
            OrderPartExprs.sizing(clause.sizing, ::walkExpr) { out.add(it) }
            OrderPartExprs.bracket(clause.bracket, ::walkExpr)
        }
        // OTO (ON_FILL) children: collect their target streams and the streams their prices read.
        opts.onFill.forEach { child ->
            when (child) {
                is Buy -> {
                    out.add(child.stream)
                    walkOpts(child.opts)
                }
                is Sell -> {
                    out.add(child.stream)
                    walkOpts(child.opts)
                }
                is Log -> child.fields.values.forEach(::walkExpr)
                else -> Unit
            }
        }
        (opts.exitHooks.onStop + opts.exitHooks.onTakeProfit + opts.exitHooks.onClose).forEach { child ->
            when (child) {
                is Buy -> {
                    out.add(child.stream)
                    walkOpts(child.opts)
                }
                is Sell -> {
                    out.add(child.stream)
                    walkOpts(child.opts)
                }
                is Log -> child.fields.values.forEach(::walkExpr)
                else -> Unit
            }
        }
    }

    fun walkAction(a: ActionAst) {
        when (a) {
            CloseAll, CancelAll -> Unit
            is Close -> out.add(a.stream)
            is Cancel -> out.add(a.stream)
            is Buy -> {
                out.add(a.stream)
                walkOpts(a.opts)
            }
            is Sell -> {
                out.add(a.stream)
                walkOpts(a.opts)
            }
            is Log -> a.fields.values.forEach { walkExpr(it) }
            is Block -> a.actions.forEach { walkAction(it) }
            is OcoEntry -> {
                walkAction(a.leg1)
                walkAction(a.leg2)
            }
            is com.qkt.dsl.ast.Resize -> {
                out.add(a.stream)
                OrderPartExprs.sizing(a.target, ::walkExpr) { out.add(it) }
                a.minStep?.let { walkExpr(it) }
            }
            is com.qkt.dsl.ast.Latch -> {
                out.add(a.stream)
                a.entries.mapNotNullTo(out) { it.stream }
            }
        }
    }

    walkExpr(rule.cond)
    walkAction(rule.action)
    return out.toSet()
}
