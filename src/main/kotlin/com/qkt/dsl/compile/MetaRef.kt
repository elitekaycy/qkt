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
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SessionWindow
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen

/**
 * Phase 39: one meta-field reference collected from a strategy AST.
 *
 * Captured at compile time and replayed at [CompiledStrategy.bindToHub] against the
 * [com.qkt.instrument.InstrumentRegistry] — so a strategy that says `gold.tick_size`
 * but runs without a registry entry for `EXNESS:XAUUSD` fails to bind with a single
 * pointed error instead of silently misbehaving at first eval.
 */
internal data class MetaRef(
    val stream: String,
    val field: String,
    val qktSymbol: String,
)

/**
 * Walk every [ExprAst] reachable from [ast] and return the meta-field [StreamFieldRef]s
 * paired with the `HubKey.qktSymbol` they resolve against. Exhaustive over the sealed
 * `ExprAst` / `ActionAst` / `SizingAst` / `OrderTypeAst` / `ChildPriceAst` / `TifAst` /
 * `StackAst` hierarchies — `when` blocks omit `else` so any new variant breaks the build.
 */
internal fun collectMetaRefs(
    ast: StrategyAst,
    streams: Map<String, HubKey>,
): List<MetaRef> {
    val out = mutableListOf<MetaRef>()

    fun walkExpr(e: ExprAst) {
        when (e) {
            is NumLit, is BoolLit, is StringLit -> Unit
            is Ref, is NowAccessor, is CalendarWindow, is SessionWindow,
            is AccountRef, is StreakRef, is TradesRef, is CooldownRef, is PositionRef, is StateAccessor,
            is SequenceAccessor,
            StackEntryRef, EntryQty, LastTradingDayOfMonth, is com.qkt.dsl.ast.ExitRef,
            -> Unit
            is StreamFieldRef -> {
                if (e.field in ExprCompiler.META_FIELDS) {
                    val sym = streams[e.stream]?.qktSymbol
                    if (sym != null) out.add(MetaRef(e.stream, e.field, sym))
                }
            }
            is IndicatorCall -> e.args.forEach { walkExpr(it) }
            is BinaryOp -> {
                walkExpr(e.lhs)
                walkExpr(e.rhs)
            }
            is UnaryOp -> walkExpr(e.arg)
            is IsNull -> walkExpr(e.expr)
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
                e.branches.forEach { (cond, value) ->
                    walkExpr(cond)
                    walkExpr(value)
                }
                walkExpr(e.elseExpr)
            }
            is Aggregate -> walkExpr(e.series)
            is FuncCall -> e.args.forEach { walkExpr(it) }
        }
    }

    fun walkOpts(opts: ActionOpts) {
        OrderPartExprs.sizing(opts.sizing, ::walkExpr)
        OrderPartExprs.orderType(opts.orderType, ::walkExpr)
        OrderPartExprs.tif(opts.tif, ::walkExpr)
        OrderPartExprs.bracket(opts.bracket, ::walkExpr)
        OrderPartExprs.oco(opts.oco, ::walkExpr)
        OrderPartExprs.stack(opts.stack, ::walkExpr)
        opts.stackAts.forEach { clause ->
            walkExpr(clause.mfeThreshold)
            clause.maeRecoverDistance?.let { walkExpr(it) }
            OrderPartExprs.sizing(clause.sizing, ::walkExpr)
            OrderPartExprs.bracket(clause.bracket, ::walkExpr)
        }
        // OTO (ON_FILL) children carry their own expressions (sizing, prices).
        opts.onFill.forEach { child ->
            when (child) {
                is Buy -> walkOpts(child.opts)
                is Sell -> walkOpts(child.opts)
                is Log -> child.fields.values.forEach(::walkExpr)
                else -> Unit
            }
        }
        (opts.exitHooks.onStop + opts.exitHooks.onTakeProfit + opts.exitHooks.onClose).forEach { child ->
            when (child) {
                is Buy -> walkOpts(child.opts)
                is Sell -> walkOpts(child.opts)
                is Log -> child.fields.values.forEach(::walkExpr)
                else -> Unit
            }
        }
    }

    fun walkAction(a: ActionAst) {
        when (a) {
            CloseAll, CancelAll -> Unit
            is Close -> Unit
            is Cancel -> Unit
            is Buy -> walkOpts(a.opts)
            is Sell -> walkOpts(a.opts)
            is Log -> a.fields.values.forEach { walkExpr(it) }
            is Block -> a.actions.forEach { walkAction(it) }
            is OcoEntry -> {
                walkAction(a.leg1)
                walkAction(a.leg2)
            }
            is com.qkt.dsl.ast.Resize -> {
                OrderPartExprs.sizing(a.target, ::walkExpr)
                a.minStep?.let { walkExpr(it) }
            }
            is com.qkt.dsl.ast.Latch -> Unit
        }
    }

    fun walkRule(r: RuleAst) {
        when (r) {
            is WhenThen -> {
                walkExpr(r.cond)
                walkAction(r.action)
            }
        }
    }

    ast.lets.forEach { walkExpr(it.expr) }
    ast.rules.forEach { walkRule(it) }
    ast.sequences.forEach { sequence -> sequence.stages.forEach { walkExpr(it.condition) } }

    return out.distinct()
}
