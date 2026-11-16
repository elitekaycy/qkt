package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.sets.SetFunction
import java.math.BigDecimal

/**
 * Compiles a cross-member function lowered over a set's members ([SetFunction.lowered]): one argument per member,
 * `RANK_OF` with this member's own value first. Members whose value is Undefined (warming up, no bar yet) are left
 * out, as SQL aggregates skip null, so one cold member never blanks the whole cross-section:
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
        return when (fn) {
            SetFunction.RANK_OF -> rank(args[0], args.subList(1, args.size))
            SetFunction.SUM_OF -> fold(args, BigDecimal::add) { total, _ -> total }
            SetFunction.AVG_OF -> fold(args, BigDecimal::add) { total, n -> total.divide(BigDecimal(n), Money.CONTEXT) }
            SetFunction.MAX_OF -> fold(args, BigDecimal::max) { most, _ -> most }
            SetFunction.MIN_OF -> fold(args, BigDecimal::min) { least, _ -> least }
            SetFunction.COUNT_OF -> count(args)
        }
    }

    private fun rank(
        self: CompiledExpr,
        members: List<CompiledExpr>,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            val own = self.evaluate(ctx)
            if (own !is Value.Num) {
                Value.Undefined
            } else {
                var above = 0
                for (member in members) {
                    val v = member.evaluate(ctx)
                    if (v is Value.Num && v.v > own.v) above++
                }
                Value.Num(BigDecimal(1 + above))
            }
        }

    private fun fold(
        members: List<CompiledExpr>,
        combine: (BigDecimal, BigDecimal) -> BigDecimal,
        finish: (BigDecimal, Int) -> BigDecimal,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            var acc: BigDecimal? = null
            var defined = 0
            for (member in members) {
                val v = member.evaluate(ctx)
                if (v is Value.Num) {
                    acc = if (acc == null) v.v else combine(acc, v.v)
                    defined++
                }
            }
            if (acc == null) Value.Undefined else Value.Num(finish(acc, defined))
        }

    private fun count(members: List<CompiledExpr>): CompiledExpr =
        CompiledExpr { ctx ->
            var holding = 0
            for (member in members) {
                if (member.evaluate(ctx) == Value.TRUE) holding++
            }
            Value.Num(BigDecimal(holding))
        }
}
