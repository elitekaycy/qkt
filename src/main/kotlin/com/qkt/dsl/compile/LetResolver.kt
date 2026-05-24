package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.UnaryOp

class LetResolver(
    lets: List<LetDecl>,
) {
    private val table: Map<String, ExprAst> = lets.associate { it.name to it.expr }

    init {
        require(table.size == lets.size) {
            "Duplicate LET name in: ${lets.map { it.name }}"
        }
    }

    fun resolve(expr: ExprAst): ExprAst =
        when (expr) {
            is Ref -> {
                if (expr.name == com.qkt.dsl.kotlin.SYMBOL_PLACEHOLDER_NAME) {
                    error("SYMBOL placeholder is only valid inside DEFAULTS block")
                }
                if (expr.snapshot != null) {
                    if (!table.containsKey(expr.name)) error("Unknown LET reference: ${expr.name}")
                    expr
                } else {
                    val target = table[expr.name] ?: error("Unknown reference: ${expr.name}")
                    resolve(target)
                }
            }
            is BinaryOp -> BinaryOp(expr.op, resolve(expr.lhs), resolve(expr.rhs))
            is UnaryOp -> UnaryOp(expr.op, resolve(expr.arg))
            is CmpOp -> CmpOp(expr.op, resolve(expr.lhs), resolve(expr.rhs))
            is IndicatorCall -> IndicatorCall(expr.name, expr.args.map { resolve(it) })
            is Between -> Between(resolve(expr.v), resolve(expr.lo), resolve(expr.hi))
            is InList -> InList(resolve(expr.v), expr.members.map { resolve(it) })
            is Crosses -> Crosses(expr.direction, resolve(expr.lhs), resolve(expr.rhs))
            is CaseWhen ->
                CaseWhen(
                    expr.branches.map { resolve(it.first) to resolve(it.second) },
                    resolve(expr.elseExpr),
                )
            is Aggregate -> Aggregate(expr.fn, resolve(expr.series), expr.window)
            is FuncCall -> FuncCall(expr.name, expr.args.map { resolve(it) })
            is NumLit, is BoolLit, is StringLit, is StreamFieldRef, is AccountRef,
            is PositionRef, is StateAccessor, is StackEntryRef, is NowAccessor,
            EntryQty,
            -> expr
        }
}
