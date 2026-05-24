package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.RuleAst
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
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
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
            is Ref, is NowAccessor, is AccountRef, is PositionRef, is StateAccessor, StackEntryRef -> Unit
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

    fun walkSizing(s: SizingAst?) {
        when (s) {
            null -> Unit
            is SizeQty -> walkExpr(s.expr)
            is SizeNotional -> walkExpr(s.usd)
            is SizePctEquity -> walkExpr(s.frac)
            is SizePctBalance -> walkExpr(s.frac)
            is SizeRiskFrac -> walkExpr(s.frac)
            is SizeRiskAbs -> walkExpr(s.usd)
            is SizePositionFull -> Unit
        }
    }

    fun walkOrderType(o: OrderTypeAst?) {
        when (o) {
            null -> Unit
            Market -> Unit
            is Limit -> walkExpr(o.price)
            is Stop -> walkExpr(o.price)
            is StopLimit -> {
                walkExpr(o.stopPrice)
                walkExpr(o.limitPrice)
            }
            is TrailingBy -> walkExpr(o.distance)
            is TrailingPct -> walkExpr(o.frac)
        }
    }

    fun walkChildPrice(c: ChildPriceAst?) {
        when (c) {
            null -> Unit
            is ChildAt -> walkExpr(c.price)
            is ChildBy -> walkExpr(c.distance)
            is ChildPct -> walkExpr(c.frac)
            is ChildRr -> walkExpr(c.multiplier)
        }
    }

    fun walkBracket(b: BracketAst?) {
        if (b == null) return
        walkChildPrice(b.stopLoss)
        walkChildPrice(b.takeProfit)
    }

    fun walkOco(o: OcoAst?) {
        if (o == null) return
        walkChildPrice(o.stop)
        walkChildPrice(o.limit)
    }

    fun walkTif(t: TifAst?) {
        when (t) {
            null -> Unit
            Gtc, Ioc, Fok, Day -> Unit
            is Gtd -> walkExpr(t.until)
        }
    }

    fun walkStack(s: StackAst?) {
        when (s) {
            null -> Unit
            is StackSpacing -> walkExpr(s.spacing)
            is StackLayers ->
                s.layers.forEach { layer ->
                    walkSizing(layer.sizing)
                    walkOrderType(layer.orderType)
                    layer.at?.let { walkExpr(it) }
                }
        }
    }

    fun walkOpts(opts: ActionOpts) {
        walkSizing(opts.sizing)
        walkOrderType(opts.orderType)
        walkTif(opts.tif)
        walkBracket(opts.bracket)
        walkOco(opts.oco)
        walkStack(opts.stack)
        opts.stackAts.forEach { clause ->
            walkExpr(clause.mfeThreshold)
            walkSizing(clause.sizing)
            walkBracket(clause.bracket)
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

    return out.distinct()
}
