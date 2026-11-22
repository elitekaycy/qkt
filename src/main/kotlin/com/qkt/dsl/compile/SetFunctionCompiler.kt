package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.sets.SetFunction
import java.math.BigDecimal

/**
 * Compiles a cross-member function lowered over a set's members ([SetFunction.lowered]): one argument per member,
 * `RANK_OF` with this member's own value first. Members whose value is Undefined (warming up, no bar yet) are left
 * out, as SQL aggregates skip null, so one cold member never blanks the whole cross-section ([reduce]):
 *
 * - `RANK_OF`: 1 + the members strictly above this member's value (ties share a rank); Undefined when this member's
 *   own value is.
 * - `SUM_OF`, `AVG_OF`, `MAX_OF`, `MIN_OF`: over the defined members; Undefined when none is.
 * - `COUNT_OF`: the members whose test is true; 0 when none is.
 */
internal object SetFunctionCompiler {
    fun compile(
        fn: SetFunction,
        call: FuncCall,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val args = call.args.map { exprs.compile(it, ruleAlias) }
        val own = if (fn.selfFirst) args[0] else null
        val members = if (fn.selfFirst) args.subList(1, args.size) else args
        return CompiledExpr { ctx ->
            reduce(fn, own?.evaluate(ctx) ?: Value.Undefined, members.map { it.evaluate(ctx) })
        }
    }

    /** [fn] over the members' [values], [own] being this member's value (read by `RANK_OF` only). */
    fun reduce(
        fn: SetFunction,
        own: Value,
        values: List<Value>,
    ): Value =
        when (fn) {
            SetFunction.RANK_OF -> rank(own, values)
            SetFunction.SUM_OF -> fold(values, BigDecimal::add) { total, _ -> total }
            SetFunction.AVG_OF ->
                fold(
                    values,
                    BigDecimal::add,
                ) { total, n -> total.divide(BigDecimal(n), Money.CONTEXT) }
            SetFunction.MAX_OF -> fold(values, BigDecimal::max) { most, _ -> most }
            SetFunction.MIN_OF -> fold(values, BigDecimal::min) { least, _ -> least }
            SetFunction.COUNT_OF -> Value.Num(BigDecimal(values.count { it == Value.TRUE }))
        }

    private fun rank(
        own: Value,
        values: List<Value>,
    ): Value =
        if (own !is Value.Num) {
            Value.Undefined
        } else {
            Value.Num(BigDecimal(1 + values.count { it is Value.Num && it.v > own.v }))
        }

    private fun fold(
        values: List<Value>,
        combine: (BigDecimal, BigDecimal) -> BigDecimal,
        finish: (BigDecimal, Int) -> BigDecimal,
    ): Value {
        var acc: BigDecimal? = null
        var defined = 0
        for (v in values) {
            if (v is Value.Num) {
                acc = if (acc == null) v.v else combine(acc, v.v)
                defined++
            }
        }
        return if (acc == null) Value.Undefined else Value.Num(finish(acc, defined))
    }
}
