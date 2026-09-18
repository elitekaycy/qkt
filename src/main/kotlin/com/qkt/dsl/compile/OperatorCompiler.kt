package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal

/**
 * Compiles the operator forms: arithmetic (`+ - * /`, unary minus), Kleene three-valued
 * `AND`/`OR`/`NOT`, comparisons, and `CASE WHEN`. Operands compile once through [ExprCompiler];
 * a non-numeric or undefined operand yields [Value.Undefined] rather than an error.
 */
internal object OperatorCompiler {
    fun compileBinary(
        op: BinaryOp,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val l = exprs.compile(op.lhs, ruleAlias)
        val r = exprs.compile(op.rhs, ruleAlias)
        return when (op.op) {
            BinOp.ADD -> numericBinary(l, r) { a, b -> a.add(b, Money.CONTEXT) }
            BinOp.SUB -> numericBinary(l, r) { a, b -> a.subtract(b, Money.CONTEXT) }
            BinOp.MUL -> numericBinary(l, r) { a, b -> a.multiply(b, Money.CONTEXT) }
            BinOp.DIV ->
                CompiledExpr { ctx ->
                    val lv = l.evaluate(ctx)
                    val rv = r.evaluate(ctx)
                    if (lv !is Value.Num || rv !is Value.Num || rv.v.signum() == 0) {
                        Value.Undefined
                    } else {
                        Value.Num(lv.v.divide(rv.v, Money.CONTEXT))
                    }
                }
            BinOp.AND -> kleeneAnd(l, r)
            BinOp.OR -> kleeneOr(l, r)
        }
    }

    private fun numericBinary(
        l: CompiledExpr,
        r: CompiledExpr,
        op: (BigDecimal, BigDecimal) -> BigDecimal,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            if (lv !is Value.Num || rv !is Value.Num) Value.Undefined else Value.Num(op(lv.v, rv.v))
        }

    // Kleene three-valued logic: a side that is Undefined (warming indicator, missing
    // cross-stream bar) only poisons the result when it could change it. TRUE OR x is
    // TRUE whatever x turns out to be; FALSE AND x is FALSE. Without this, a session
    // gate like `warm_signal OR fallback` is silently suppressed for the whole warmup.
    private fun kleeneAnd(
        l: CompiledExpr,
        r: CompiledExpr,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            when {
                lv is Value.Bool && !lv.v -> Value.of(false)
                rv is Value.Bool && !rv.v -> Value.of(false)
                lv is Value.Bool && rv is Value.Bool -> Value.of(true)
                else -> Value.Undefined
            }
        }

    private fun kleeneOr(
        l: CompiledExpr,
        r: CompiledExpr,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            when {
                lv is Value.Bool && lv.v -> Value.of(true)
                rv is Value.Bool && rv.v -> Value.of(true)
                lv is Value.Bool && rv is Value.Bool -> Value.of(false)
                else -> Value.Undefined
            }
        }

    fun compileUnary(
        op: UnaryOp,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val a = exprs.compile(op.arg, ruleAlias)
        return when (op.op) {
            UnOp.NEG ->
                CompiledExpr { ctx ->
                    val v = a.evaluate(ctx)
                    if (v !is Value.Num) Value.Undefined else Value.Num(v.v.negate(Money.CONTEXT))
                }
            UnOp.NOT ->
                CompiledExpr { ctx ->
                    val v = a.evaluate(ctx)
                    if (v !is Value.Bool) Value.Undefined else Value.of(!v.v)
                }
        }
    }

    fun compileCmp(
        op: CmpOp,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val l = exprs.compile(op.lhs, ruleAlias)
        val r = exprs.compile(op.rhs, ruleAlias)
        return CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            when {
                lv is Value.Num && rv is Value.Num -> {
                    val c = lv.v.compareTo(rv.v)
                    Value.of(
                        when (op.op) {
                            Cmp.GT -> c > 0
                            Cmp.LT -> c < 0
                            Cmp.GE -> c >= 0
                            Cmp.LE -> c <= 0
                            Cmp.EQ -> c == 0
                            Cmp.NE -> c != 0
                        },
                    )
                }
                lv is Value.Str && rv is Value.Str && op.op == Cmp.EQ -> Value.of(lv.v == rv.v)
                lv is Value.Str && rv is Value.Str && op.op == Cmp.NE -> Value.of(lv.v != rv.v)
                else -> Value.Undefined
            }
        }
    }

    fun compileCaseWhen(
        expr: CaseWhen,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        // Parallel arrays: a Pair list destructures (allocating an iterator) per evaluation.
        val conds = expr.branches.map { exprs.compile(it.first, ruleAlias) }.toTypedArray()
        val bodies = expr.branches.map { exprs.compile(it.second, ruleAlias) }.toTypedArray()
        val elseE = exprs.compile(expr.elseExpr, ruleAlias)
        return CompiledExpr { ctx ->
            // Evaluate EVERY branch condition each bar — not just up to the first match — so a
            // stateful operator inside a later condition (CROSSES, aggregates) sees every bar and
            // its prev-state stays correct. Short-circuiting on the first match left those nodes a
            // bar behind, misdetecting crossings (#390 DSL-17). The result is still the first
            // matching branch's body (or the else).
            var chosen: CompiledExpr? = null
            for (i in conds.indices) {
                val cv = conds[i].evaluate(ctx)
                if (chosen == null && cv is Value.Bool && cv.v) chosen = bodies[i]
            }
            (chosen ?: elseE).evaluate(ctx)
        }
    }
}
