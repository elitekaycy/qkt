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
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.DefaultsBlock
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
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SessionWindow
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.UnaryOp

/**
 * Rebuilds the DSL expression tree and every expression-bearing sub-tree (sizing, order type,
 * TIF, child price, bracket, OCO, stack, stack-at, action options, defaults block) by recursing
 * once through each node. The only behavior that varies between callers is how a [Ref] leaf is
 * handled — supplied as [onRef]; the composite recursion is shared.
 *
 * Every `when` is exhaustive with no `else`, so adding a new expression-bearing AST node is a
 * single compile error here instead of a silent miss across several near-identical walkers.
 *
 * e.g. param substitution passes `onRef = { values[it.name] ?: it }`; LET resolution looks the
 * ref up in its binding table; defaults-merge rewrites the `SYMBOL` placeholder to a field ref.
 */
class ExprTransform(
    /**
     * Hook for stream field references. Defaults to identity, and sits FIRST so that every
     * existing `ExprTransform { ref -> ... }` call still binds its trailing lambda to [onRef].
     * Hub expansion uses it to redirect `cal.surprise` onto the hidden per-field alias without
     * a second walker that would drift the next time a node type is added.
     */
    private val onStreamField: (StreamFieldRef) -> ExprAst = { it },
    private val onRef: (Ref) -> ExprAst,
) {
    private val orderSpecs = OrderSpecTransform(this)
    private val childPrices = ChildPriceTransform(this)
    private val stacks = StackTransform(this)
    private val latches = LatchTransform(this)

    fun expr(e: ExprAst): ExprAst =
        when (e) {
            is Ref -> onRef(e)
            is StreamFieldRef -> onStreamField(e)
            is BinaryOp -> BinaryOp(e.op, expr(e.lhs), expr(e.rhs))
            is UnaryOp -> UnaryOp(e.op, expr(e.arg))
            is CmpOp -> CmpOp(e.op, expr(e.lhs), expr(e.rhs))
            is IndicatorCall -> IndicatorCall(e.name, e.args.map(::expr))
            is Between -> Between(expr(e.v), expr(e.lo), expr(e.hi))
            is InList -> InList(expr(e.v), e.members.map(::expr))
            is Crosses -> Crosses(e.direction, expr(e.lhs), expr(e.rhs))
            is CaseWhen ->
                CaseWhen(
                    e.branches.map { expr(it.first) to expr(it.second) },
                    expr(e.elseExpr),
                )
            is Aggregate -> Aggregate(e.fn, expr(e.series), e.window)
            is FuncCall -> FuncCall(e.name, e.args.map(::expr))
            is IsNull -> IsNull(expr(e.expr), e.negated)
            is NumLit, is BoolLit, is StringLit, is AccountRef, is StreakRef, is TradesRef,
            is CooldownRef, is PositionRef, is StateAccessor, is StackEntryRef, is NowAccessor,
            is SequenceAccessor,
            is CalendarWindow,
            is SessionWindow,
            LastTradingDayOfMonth,
            EntryQty, is com.qkt.dsl.ast.ExitRef,
            -> e
        }

    fun sizing(s: SizingAst): SizingAst = orderSpecs.sizing(s)

    fun orderType(o: OrderTypeAst): OrderTypeAst = orderSpecs.orderType(o)

    fun tif(t: TifAst): TifAst = orderSpecs.tif(t)

    fun childPrice(cp: ChildPriceAst): ChildPriceAst = childPrices.childPrice(cp)

    fun bracket(b: BracketAst): BracketAst = childPrices.bracket(b)

    fun oco(o: OcoAst): OcoAst = childPrices.oco(o)

    fun stack(s: StackAst): StackAst = stacks.stack(s)

    fun stackLayer(l: StackLayer): StackLayer = stacks.stackLayer(l)

    fun stackAt(c: StackAtClause): StackAtClause = stacks.stackAt(c)

    fun opts(o: ActionOpts): ActionOpts =
        ActionOpts(
            sizing = o.sizing?.let(::sizing),
            orderType = o.orderType?.let(::orderType),
            tif = o.tif?.let(::tif),
            bracket = o.bracket?.let(::bracket),
            oco = o.oco?.let(::oco),
            stack = o.stack?.let(::stack),
            stackAts = o.stackAts.map(::stackAt),
            onFill = o.onFill.map(::action),
            times = o.times?.let(::expr),
            exitAfter = o.exitAfter,
            exitHooks =
                com.qkt.dsl.ast.ExitHooksAst(
                    onStop = o.exitHooks.onStop.map(::action),
                    onTakeProfit = o.exitHooks.onTakeProfit.map(::action),
                    onClose = o.exitHooks.onClose.map(::action),
                ),
        )

    fun action(a: ActionAst): ActionAst =
        when (a) {
            is Buy -> a.copy(opts = opts(a.opts))
            is Sell -> a.copy(opts = opts(a.opts))
            is Block -> Block(a.actions.map(::action))
            is OcoEntry -> OcoEntry(action(a.leg1), action(a.leg2))
            is Log -> a.copy(fields = a.fields.mapValues { expr(it.value) })
            is Close, is Cancel, CloseAll, CancelAll -> a
            is com.qkt.dsl.ast.Resize -> a.copy(target = sizing(a.target), minStep = a.minStep?.let(::expr))
            is com.qkt.dsl.ast.Latch -> latches.latch(a)
        }

    fun defaultsBlock(d: DefaultsBlock): DefaultsBlock =
        DefaultsBlock(
            sizing = d.sizing?.let(::sizing),
            orderType = d.orderType?.let(::orderType),
            tif = d.tif?.let(::tif),
            stopLoss = d.stopLoss?.let(::childPrice),
            takeProfit = d.takeProfit?.let(::childPrice),
            trailing = d.trailing?.let(::orderType),
        )
}
