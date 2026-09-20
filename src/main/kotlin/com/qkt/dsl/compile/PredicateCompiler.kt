package com.qkt.dsl.compile

import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IsNull

/**
 * Compiles the predicate forms `CROSSES`, `IN (...)`, `IS [NOT] NULL` and `BETWEEN`. `CROSSES`
 * keeps its previous-bar side in a [CrossesState] owned by the compiled closure.
 */
internal object PredicateCompiler {
    fun compileCrosses(
        c: Crosses,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val l = exprs.compile(c.lhs, ruleAlias)
        val r = exprs.compile(c.rhs, ruleAlias)
        val state = CrossesState()
        return CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            if (lv !is Value.Num || rv !is Value.Num) {
                Value.Undefined
            } else {
                val above = lv.v.compareTo(rv.v) > 0
                state.update(above, c.direction)
            }
        }
    }

    fun compileInList(
        expr: InList,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val v = exprs.compile(expr.v, ruleAlias)
        val members = expr.members.map { exprs.compile(it, ruleAlias) }
        return CompiledExpr { ctx ->
            val vv = v.evaluate(ctx)
            if (vv !is Value.Num) {
                Value.Undefined
            } else {
                var hit = false
                for (m in members) {
                    val mv = m.evaluate(ctx)
                    if (mv is Value.Num && mv.v.compareTo(vv.v) == 0) {
                        hit = true
                        break
                    }
                }
                Value.of(hit)
            }
        }
    }

    fun compileIsNull(
        expr: IsNull,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val inner = exprs.compile(expr.expr, ruleAlias)
        return CompiledExpr { ctx ->
            val v = inner.evaluate(ctx)
            val isUndef = v is Value.Undefined
            Value.of(if (expr.negated) !isUndef else isUndef)
        }
    }

    fun compileBetween(
        b: Between,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val v = exprs.compile(b.v, ruleAlias)
        val lo = exprs.compile(b.lo, ruleAlias)
        val hi = exprs.compile(b.hi, ruleAlias)
        return CompiledExpr { ctx ->
            val vv = v.evaluate(ctx)
            val lov = lo.evaluate(ctx)
            val hiv = hi.evaluate(ctx)
            if (vv !is Value.Num || lov !is Value.Num || hiv !is Value.Num) {
                Value.Undefined
            } else {
                Value.of(vv.v >= lov.v && vv.v <= hiv.v)
            }
        }
    }
}
