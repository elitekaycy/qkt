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
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Log
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

/**
 * Replaces every `PARAM` reference in a strategy with the param's literal default,
 * across conditions, LET right-hand sides, schedules, and rule actions (including the
 * expressions buried in SIZING, brackets, order types, TIF, OCO, and stack clauses).
 *
 * Runs before the compiler so the rest of compilation never sees a `PARAM` — a bare
 * `Ref("riskPct")` becomes the `NumLit` it was declared as. e.g. given
 * `PARAM riskPct = 0.01`, a `SIZING RISK $ (ACCOUNT.equity * riskPct)` is rewritten to
 * `... * 0.01` before [AstCompiler] runs.
 *
 * A strategy with no `PARAM` declarations is returned unchanged.
 */
object ParamSubstitution {
    fun apply(ast: StrategyAst): StrategyAst {
        val values: Map<String, ExprAst> = ast.params.associate { it.name to it.value }
        if (values.isEmpty()) return ast
        val s = Substituter(values)
        return ast.copy(
            params = emptyList(),
            lets = ast.lets.map { it.copy(expr = s.subst(it.expr)) },
            rules = ast.rules.map { s.subst(it) },
            schedules = ast.schedules.map { it.copy(action = s.subst(it.action)) },
            defaults = ast.defaults?.let { s.subst(it) },
        )
    }

    private class Substituter(
        private val values: Map<String, ExprAst>,
    ) {
        fun subst(rule: RuleAst): RuleAst =
            when (rule) {
                is WhenThen -> WhenThen(cond = subst(rule.cond), action = subst(rule.action))
            }

        fun subst(action: ActionAst): ActionAst =
            when (action) {
                is Buy -> action.copy(opts = subst(action.opts))
                is Sell -> action.copy(opts = subst(action.opts))
                is Block -> Block(action.actions.map { subst(it) })
                is OcoEntry -> OcoEntry(subst(action.leg1), subst(action.leg2))
                is Log -> action.copy(fields = action.fields.mapValues { subst(it.value) })
                is Close, is Cancel, CloseAll, CancelAll -> action
            }

        private fun subst(opts: ActionOpts): ActionOpts =
            ActionOpts(
                sizing = opts.sizing?.let { subst(it) },
                orderType = opts.orderType?.let { subst(it) },
                tif = opts.tif?.let { subst(it) },
                bracket = opts.bracket?.let { subst(it) },
                oco = opts.oco?.let { subst(it) },
                stack = opts.stack?.let { subst(it) },
                stackAts = opts.stackAts.map { subst(it) },
            )

        fun subst(d: DefaultsBlock): DefaultsBlock =
            DefaultsBlock(
                sizing = d.sizing?.let { subst(it) },
                orderType = d.orderType?.let { subst(it) },
                tif = d.tif?.let { subst(it) },
                stopLoss = d.stopLoss?.let { subst(it) },
                takeProfit = d.takeProfit?.let { subst(it) },
                trailing = d.trailing?.let { subst(it) },
            )

        private fun subst(sizing: SizingAst): SizingAst =
            when (sizing) {
                is SizeQty -> SizeQty(subst(sizing.expr))
                is SizeNotional -> SizeNotional(subst(sizing.usd))
                is SizePctEquity -> SizePctEquity(subst(sizing.frac))
                is SizePctBalance -> SizePctBalance(subst(sizing.frac))
                is SizeRiskFrac -> SizeRiskFrac(subst(sizing.frac))
                is SizeRiskAbs -> SizeRiskAbs(subst(sizing.usd))
                is SizePositionFull -> sizing
            }

        private fun subst(orderType: OrderTypeAst): OrderTypeAst =
            when (orderType) {
                com.qkt.dsl.ast.Market -> orderType
                is Limit -> Limit(subst(orderType.price))
                is Stop -> Stop(subst(orderType.price))
                is StopLimit -> StopLimit(subst(orderType.stopPrice), subst(orderType.limitPrice))
                is TrailingBy -> TrailingBy(subst(orderType.distance))
                is TrailingPct -> TrailingPct(subst(orderType.frac))
            }

        private fun subst(tif: TifAst): TifAst =
            when (tif) {
                is Gtd -> Gtd(subst(tif.until))
                com.qkt.dsl.ast.Gtc, com.qkt.dsl.ast.Ioc, com.qkt.dsl.ast.Fok, com.qkt.dsl.ast.Day -> tif
            }

        private fun subst(bracket: BracketAst): BracketAst =
            BracketAst(
                stopLoss = bracket.stopLoss?.let { subst(it) },
                takeProfit = bracket.takeProfit?.let { subst(it) },
            )

        private fun subst(oco: OcoAst): OcoAst = OcoAst(subst(oco.stop), subst(oco.limit))

        private fun subst(child: ChildPriceAst): ChildPriceAst =
            when (child) {
                is ChildAt -> ChildAt(subst(child.price))
                is ChildBy -> ChildBy(subst(child.distance))
                is ChildPct -> ChildPct(subst(child.frac))
                is ChildRr -> ChildRr(subst(child.multiplier))
                is ChildArmedTrail -> ChildArmedTrail(subst(child.trailDistance), subst(child.mfeThreshold))
            }

        private fun subst(stack: StackAst): StackAst =
            when (stack) {
                is StackSpacing -> stack.copy(spacing = subst(stack.spacing))
                is StackLayers -> stack.copy(layers = stack.layers.map { subst(it) })
            }

        private fun subst(layer: StackLayer): StackLayer =
            StackLayer(
                sizing = subst(layer.sizing),
                orderType = layer.orderType?.let { subst(it) },
                at = layer.at?.let { subst(it) },
            )

        private fun subst(clause: StackAtClause): StackAtClause =
            StackAtClause(
                mfeThreshold = subst(clause.mfeThreshold),
                withinDuration = clause.withinDuration,
                sizing = subst(clause.sizing),
                bracket = subst(clause.bracket),
            )

        fun subst(expr: ExprAst): ExprAst =
            when (expr) {
                is Ref -> values[expr.name] ?: expr
                is BinaryOp -> BinaryOp(expr.op, subst(expr.lhs), subst(expr.rhs))
                is UnaryOp -> UnaryOp(expr.op, subst(expr.arg))
                is CmpOp -> CmpOp(expr.op, subst(expr.lhs), subst(expr.rhs))
                is IndicatorCall -> IndicatorCall(expr.name, expr.args.map { subst(it) })
                is Between -> Between(subst(expr.v), subst(expr.lo), subst(expr.hi))
                is InList -> InList(subst(expr.v), expr.members.map { subst(it) })
                is Crosses -> Crosses(expr.direction, subst(expr.lhs), subst(expr.rhs))
                is CaseWhen ->
                    CaseWhen(
                        expr.branches.map { subst(it.first) to subst(it.second) },
                        subst(expr.elseExpr),
                    )
                is Aggregate -> Aggregate(expr.fn, subst(expr.series), expr.window)
                is FuncCall -> FuncCall(expr.name, expr.args.map { subst(it) })
                is IsNull -> IsNull(subst(expr.expr), expr.negated)
                is NumLit, is BoolLit, is StringLit, is StreamFieldRef, is AccountRef,
                is PositionRef, is StateAccessor, is StackEntryRef, is NowAccessor,
                EntryQty,
                -> expr
            }
    }
}
