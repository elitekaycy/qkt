package com.qkt.cli

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.LastTradingDayOfMonth
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchSensor
import com.qkt.dsl.ast.LatchStop
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
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SessionWindow
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
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
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
import com.qkt.dsl.stdlib.IndicatorRegistry

internal data class StrategyDataRequirements(
    val quoteAliases: Set<String>,
    val volumeAliases: Set<String>,
)

internal object StrategyDataRequirementScanner {
    private val quoteFields = setOf("bid", "ask", "spread")

    fun scan(ast: StrategyAst): StrategyDataRequirements {
        val quoteAliases = mutableSetOf<String>()
        val volumeAliases = mutableSetOf<String>()

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
                is SessionWindow,
                StackEntryRef,
                is StateAccessor,
                is StringLit,
                null,
                -> Unit
            }
        }

        fun walkSizing(sizing: SizingAst?) {
            when (sizing) {
                is SizeQty -> walk(sizing.expr)
                is SizeNotional -> walk(sizing.usd)
                is SizePctEquity -> walk(sizing.frac)
                is SizePctBalance -> walk(sizing.frac)
                is SizeRiskFrac -> walk(sizing.frac)
                is SizeRiskAbs -> walk(sizing.usd)
                is SizePositionFull, null -> Unit
            }
        }

        fun walkOrder(orderType: OrderTypeAst?) {
            when (orderType) {
                is Limit -> walk(orderType.price)
                is Stop -> walk(orderType.price)
                is StopLimit -> {
                    walk(orderType.stopPrice)
                    walk(orderType.limitPrice)
                }
                is TrailingBy -> walk(orderType.distance)
                is TrailingPct -> walk(orderType.frac)
                Market, null -> Unit
            }
        }

        fun walkTif(tif: TifAst?) {
            if (tif is Gtd) walk(tif.until)
        }

        fun walkChild(price: ChildPriceAst?) {
            when (price) {
                is ChildAt -> walk(price.price)
                is ChildBy -> walk(price.distance)
                is ChildPct -> walk(price.frac)
                is ChildRr -> walk(price.multiplier)
                is ChildArmedTrail -> {
                    walk(price.trailDistance)
                    walk(price.mfeThreshold)
                }
                null -> Unit
            }
        }

        fun walkBracket(bracket: BracketAst?) {
            if (bracket == null) return
            walkChild(bracket.stopLoss)
            walkChild(bracket.takeProfit)
        }

        fun walkOco(oco: OcoAst?) {
            if (oco == null) return
            walkChild(oco.stop)
            walkChild(oco.limit)
        }

        fun walkDirRel(dirRel: DirRel?) {
            if (dirRel != null) walk(dirRel.dist)
        }

        fun walkLatchBracket(bracket: LatchBracket?) {
            if (bracket == null) return
            walkDirRel(bracket.stopLoss)
            walkDirRel(bracket.takeProfit)
        }

        fun walkLatchSensor(sensor: LatchSensor) {
            when (sensor) {
                is BreakOffset -> {
                    walk(sensor.reference)
                    walk(sensor.offset)
                }
            }
        }

        fun walkLatchEntry(entry: LatchEntry) {
            when (val order = entry.order) {
                is LatchLimit -> walkDirRel(order.price)
                is LatchStop -> walkDirRel(order.price)
                LatchMarket -> Unit
            }
            walkLatchBracket(entry.bracket)
            walkSizing(entry.sizing)
        }

        fun walkLatch(latch: Latch) {
            walkLatchSensor(latch.sensor)
            latch.entries.forEach(::walkLatchEntry)
        }

        fun walkLayer(layer: StackLayer) {
            walkSizing(layer.sizing)
            walkOrder(layer.orderType)
            walk(layer.at)
        }

        fun walkStack(stack: StackAst?) {
            when (stack) {
                is StackSpacing -> walk(stack.spacing)
                is StackLayers -> stack.layers.forEach(::walkLayer)
                null -> Unit
            }
        }

        fun walkStackAt(clause: StackAtClause) {
            walk(clause.mfeThreshold)
            walkSizing(clause.sizing)
            walkBracket(clause.bracket)
        }

        lateinit var walkAction: (ActionAst) -> Unit

        fun walkOpts(opts: ActionOpts) {
            walkSizing(opts.sizing)
            walkOrder(opts.orderType)
            walkTif(opts.tif)
            walkBracket(opts.bracket)
            walkOco(opts.oco)
            walkStack(opts.stack)
            opts.stackAts.forEach(::walkStackAt)
            opts.onFill.forEach { walkAction(it) }
        }

        walkAction = { action ->
            when (action) {
                is Buy -> walkOpts(action.opts)
                is Sell -> walkOpts(action.opts)
                is Latch -> walkLatch(action)
                is Resize -> {
                    walkSizing(action.target)
                    walk(action.minStep)
                }
                is Log -> action.fields.values.forEach(::walk)
                is Block -> action.actions.forEach { walkAction(it) }
                is OcoEntry -> {
                    walkAction(action.leg1)
                    walkAction(action.leg2)
                }
                is Cancel,
                CancelAll,
                is Close,
                CloseAll,
                -> Unit
            }
        }

        fun walkRule(rule: RuleAst) {
            when (rule) {
                is WhenThen -> {
                    walk(rule.cond)
                    walkAction(rule.action)
                }
            }
        }

        ast.lets.forEach { walk(it.expr) }
        ast.params.forEach { walk(it.value) }
        ast.rules.forEach(::walkRule)
        ast.schedules.forEach { walkAction(it.action) }
        return StrategyDataRequirements(
            quoteAliases = quoteAliases,
            volumeAliases = volumeAliases,
        )
    }
}
