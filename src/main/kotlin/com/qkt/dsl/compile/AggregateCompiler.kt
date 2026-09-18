package com.qkt.dsl.compile

import com.qkt.dsl.ast.Aggregate

/**
 * Compiles a windowed aggregate (`MAX(close) SINCE OPEN`, ...): registers an [AggregateBinding]
 * in the shared bag, which updates the state per bar, and returns a closure reading that state.
 */
internal object AggregateCompiler {
    fun compile(
        agg: Aggregate,
        ruleAlias: String?,
        aggregates: AggregateBinding.Bag,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val sym = ruleAlias ?: error("Aggregate requires rule symbol context")
        val state = AggregateBinding.Bag.stateFor(agg.fn, agg.window)
        val seriesEval = exprs.compile(agg.series, ruleAlias)
        val binding = AggregateBinding(seriesEval, agg.window, state, sym)
        aggregates.add(binding)
        return CompiledExpr {
            val v = state.read()
            if (v == null) Value.Undefined else Value.Num(v)
        }
    }
}
