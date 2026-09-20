package com.qkt.dsl.compile

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
import com.qkt.dsl.ast.ExitField
import com.qkt.dsl.ast.ExitRef
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
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.UnaryOp

/**
 * Compiles a DSL expression tree into a [CompiledExpr] closure evaluated per bar or tick. This
 * class is the dispatch point: each expression kind compiles in its own collaborator
 * ([OperatorCompiler], [PredicateCompiler], [IndicatorCallCompiler], [StateAccessorCompiler],
 * [ClockExprCompiler], ...), which call back into [compile] for sub-expressions. Indicator and
 * aggregate calls register their bindings in the shared bags passed to the constructor.
 */
class ExprCompiler(
    private val bindings: IndicatorBinding.Bag = IndicatorBinding.Bag(),
    private val aggregates: AggregateBinding.Bag = AggregateBinding.Bag(),
    private val baskets: Map<String, List<String>> = emptyMap(),
    private val allowExitAccess: Boolean = false,
) {
    private val indicatorCalls = IndicatorCallCompiler(bindings, this)

    internal fun forExitHooks(): ExprCompiler =
        if (allowExitAccess) {
            this
        } else {
            ExprCompiler(bindings, aggregates, baskets, allowExitAccess = true)
        }

    fun compile(
        expr: ExprAst,
        ruleAlias: String? = null,
    ): CompiledExpr =
        when (expr) {
            // Literals box once at compile time, not per evaluation.
            is NumLit -> Value.Num(expr.value).let { v -> CompiledExpr { v } }
            is BoolLit -> Value.of(expr.value).let { v -> CompiledExpr { v } }
            is StringLit -> Value.Str(expr.value).let { v -> CompiledExpr { v } }
            is BinaryOp -> OperatorCompiler.compileBinary(expr, ruleAlias, this)
            is UnaryOp -> OperatorCompiler.compileUnary(expr, ruleAlias, this)
            is CmpOp -> OperatorCompiler.compileCmp(expr, ruleAlias, this)
            is StreamFieldRef -> StreamFieldCompiler.compile(expr)
            is IndicatorCall -> indicatorCalls.compile(expr)
            is AccountRef -> AccountStateCompiler.compileAccountRef(expr)
            is StreakRef -> AccountStateCompiler.compileStreakRef(expr)
            is TradesRef -> AccountStateCompiler.compileTradesRef(expr)
            is CooldownRef -> AccountStateCompiler.compileCooldownRef(expr)
            is PositionRef -> PositionRefCompiler.compile(expr, baskets)
            is StateAccessor -> StateAccessorCompiler.compile(expr)
            is SequenceAccessor -> SequenceAccessorCompiler.compile(expr)
            is Between -> PredicateCompiler.compileBetween(expr, ruleAlias, this)
            is InList -> PredicateCompiler.compileInList(expr, ruleAlias, this)
            is IsNull -> PredicateCompiler.compileIsNull(expr, ruleAlias, this)
            is CaseWhen -> OperatorCompiler.compileCaseWhen(expr, ruleAlias, this)
            is Crosses -> PredicateCompiler.compileCrosses(expr, ruleAlias, this)
            is FuncCall -> FuncCallCompiler.compile(expr, ruleAlias, this)
            is Ref -> SnapshotRefCompiler.compile(expr, ruleAlias)
            is Aggregate -> AggregateCompiler.compile(expr, ruleAlias, aggregates, this)
            is NowAccessor -> ClockExprCompiler.compileNow(expr)
            is CalendarWindow -> ClockExprCompiler.compileCalendarWindow(expr)
            is SessionWindow -> ClockExprCompiler.compileSessionWindow(expr)
            is LastTradingDayOfMonth -> ClockExprCompiler.compileLastTradingDayOfMonth()
            is com.qkt.dsl.ast.EntryQty ->
                error("ENTRY_QTY is only valid inside STACK_AT SIZING; got it in a non-STACK_AT expression")
            is com.qkt.dsl.ast.StackEntryRef ->
                CompiledExpr { ctx ->
                    ctx.entryPrice?.let { Value.Num(it) }
                        ?: error("'entry' is only valid in an ON_FILL child price; it refers to the parent fill price")
                }
            is ExitRef -> {
                if (!allowExitAccess) {
                    error("EXIT.${expr.field.name.lowercase()} is only valid inside ON_STOP, ON_TP, or ON_CLOSE")
                }
                CompiledExpr { ctx ->
                    val exit =
                        ctx.exitContext
                            ?: error("EXIT.${expr.field.name.lowercase()} is only valid in an exit hook")
                    when (expr.field) {
                        ExitField.PRICE -> Value.Num(exit.price)
                        ExitField.SIDE -> Value.Str(exit.side.name)
                        ExitField.QTY -> Value.Num(exit.quantity)
                        ExitField.PNL -> Value.Num(exit.pnl)
                        ExitField.REASON ->
                            Value.Str(
                                if (exit.reason == com.qkt.execution.ExitReason.TAKE_PROFIT) {
                                    "TP"
                                } else {
                                    exit.reason.name
                                },
                            )
                    }
                }
            }
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
        }

    companion object {
        val CANDLE_FIELDS: Set<String> =
            setOf("close", "open", "high", "low", "volume", "price", "bid", "ask", "spread", "value", "timestamp")
        val META_FIELDS: Set<String> =
            setOf(
                "tick_size",
                "contract_size",
                "volume_step",
                "volume_min",
                "swap_long_points",
                "swap_short_points",
            )
    }
}
