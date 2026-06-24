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
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.LastTradingDayOfMonth
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
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
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
    private val onRef: (Ref) -> ExprAst,
) {
    fun expr(e: ExprAst): ExprAst =
        when (e) {
            is Ref -> onRef(e)
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
            is NumLit, is BoolLit, is StringLit, is StreamFieldRef, is AccountRef,
            is PositionRef, is StateAccessor, is StackEntryRef, is NowAccessor,
            is CalendarWindow,
            is SessionWindow,
            LastTradingDayOfMonth,
            EntryQty,
            -> e
        }

    fun sizing(s: SizingAst): SizingAst =
        when (s) {
            is SizeQty -> SizeQty(expr(s.expr))
            is SizeNotional -> SizeNotional(expr(s.usd))
            is SizePctEquity -> SizePctEquity(expr(s.frac))
            is SizePctBalance -> SizePctBalance(expr(s.frac))
            is SizeRiskFrac -> SizeRiskFrac(expr(s.frac))
            is SizeRiskAbs -> SizeRiskAbs(expr(s.usd))
            is SizePositionFull -> s
        }

    fun orderType(o: OrderTypeAst): OrderTypeAst =
        when (o) {
            Market -> o
            is Limit -> Limit(expr(o.price))
            is Stop -> Stop(expr(o.price))
            is StopLimit -> StopLimit(expr(o.stopPrice), expr(o.limitPrice))
            is TrailingBy -> TrailingBy(expr(o.distance))
            is TrailingPct -> TrailingPct(expr(o.frac))
        }

    fun tif(t: TifAst): TifAst =
        when (t) {
            is Gtd -> Gtd(expr(t.until))
            Gtc, Ioc, Fok, Day -> t
        }

    fun childPrice(cp: ChildPriceAst): ChildPriceAst =
        when (cp) {
            is ChildAt -> ChildAt(expr(cp.price))
            is ChildBy -> ChildBy(expr(cp.distance))
            is ChildPct -> ChildPct(expr(cp.frac))
            is ChildRr -> ChildRr(expr(cp.multiplier))
            is ChildArmedTrail -> ChildArmedTrail(expr(cp.trailDistance), expr(cp.mfeThreshold))
        }

    fun bracket(b: BracketAst): BracketAst = BracketAst(b.stopLoss?.let(::childPrice), b.takeProfit?.let(::childPrice))

    fun oco(o: OcoAst): OcoAst = OcoAst(childPrice(o.stop), childPrice(o.limit))

    fun stack(s: StackAst): StackAst =
        when (s) {
            is StackSpacing -> s.copy(spacing = expr(s.spacing))
            is StackLayers -> s.copy(layers = s.layers.map(::stackLayer))
        }

    fun stackLayer(l: StackLayer): StackLayer =
        StackLayer(sizing(l.sizing), l.orderType?.let(::orderType), l.at?.let(::expr))

    fun stackAt(c: StackAtClause): StackAtClause =
        StackAtClause(expr(c.mfeThreshold), c.withinDuration, sizing(c.sizing), bracket(c.bracket))

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
        )

    fun action(a: ActionAst): ActionAst =
        when (a) {
            is Buy -> a.copy(opts = opts(a.opts))
            is Sell -> a.copy(opts = opts(a.opts))
            is Block -> Block(a.actions.map(::action))
            is OcoEntry -> OcoEntry(action(a.leg1), action(a.leg2))
            is Log -> a.copy(fields = a.fields.mapValues { expr(it.value) })
            is Close, is Cancel, CloseAll, CancelAll -> a
            is com.qkt.dsl.ast.Latch -> latch(a)
        }

    // Walk a latch's expressions so LET (and other expr transforms) reach the offset,
    // each entry's direction-relative distance, and the bracket distances. Without this
    // the latch is a passthrough and `RETRACE near` (a LET ref) never resolves to a literal.
    private fun latch(a: com.qkt.dsl.ast.Latch): com.qkt.dsl.ast.Latch =
        a.copy(
            sensor =
                when (val s = a.sensor) {
                    is com.qkt.dsl.ast.BreakOffset ->
                        com.qkt.dsl.ast
                            .BreakOffset(s.reference?.let(::expr), expr(s.offset))
                },
            entries = a.entries.map(::latchEntry),
        )

    private fun latchEntry(e: com.qkt.dsl.ast.LatchEntry): com.qkt.dsl.ast.LatchEntry =
        e.copy(
            order =
                when (val o = e.order) {
                    com.qkt.dsl.ast.LatchMarket -> o
                    is com.qkt.dsl.ast.LatchLimit ->
                        com.qkt.dsl.ast
                            .LatchLimit(dirRel(o.price))
                    is com.qkt.dsl.ast.LatchStop ->
                        com.qkt.dsl.ast
                            .LatchStop(dirRel(o.price))
                },
            bracket =
                e.bracket?.let { b ->
                    com.qkt.dsl.ast
                        .LatchBracket(b.stopLoss?.let(::dirRel), b.takeProfit?.let(::dirRel))
                },
            sizing = e.sizing?.let(::sizing),
        )

    private fun dirRel(r: com.qkt.dsl.ast.DirRel): com.qkt.dsl.ast.DirRel =
        com.qkt.dsl.ast
            .DirRel(r.sense, expr(r.dist))

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
