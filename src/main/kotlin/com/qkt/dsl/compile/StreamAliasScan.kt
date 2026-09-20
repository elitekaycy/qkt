package com.qkt.dsl.compile

import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnaryOp

/**
 * Walks [expr] and returns the stream aliases it references via [StreamFieldRef],
 * in left-to-right traversal order with duplicates removed. Used by [IndicatorCallCompiler]
 * to pick the primary alias for an expression-fed indicator binding (#174).
 */
internal fun streamAliasesIn(expr: ExprAst): List<String> {
    val out = LinkedHashSet<String>()

    fun walk(e: ExprAst) {
        when (e) {
            is StreamFieldRef -> out.add(e.stream)
            is BinaryOp -> {
                walk(e.lhs)
                walk(e.rhs)
            }
            is UnaryOp -> walk(e.arg)
            is CmpOp -> {
                walk(e.lhs)
                walk(e.rhs)
            }
            is Crosses -> {
                walk(e.lhs)
                walk(e.rhs)
            }
            is FuncCall -> for (a in e.args) walk(a)
            is IndicatorCall -> for (a in e.args) walk(a)
            is Aggregate -> walk(e.series)
            is Between -> {
                walk(e.v)
                walk(e.lo)
                walk(e.hi)
            }
            is InList -> {
                walk(e.v)
                for (m in e.members) walk(m)
            }
            is CaseWhen -> {
                for ((c, b) in e.branches) {
                    walk(c)
                    walk(b)
                }
                walk(e.elseExpr)
            }
            is IsNull -> walk(e.expr)
            else -> Unit
        }
    }
    walk(expr)
    return out.toList()
}
