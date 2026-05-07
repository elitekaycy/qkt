package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.Window

class AggregateBinding(
    val seriesEvaluator: CompiledExpr,
    val window: Window,
    val state: AggregateState,
    val ruleSymbol: String,
) {
    fun update(ctx: EvalContext) {
        val v = seriesEvaluator.evaluate(ctx)
        if (v is Value.Num) state.update(v.v)
    }

    fun resetIfSinceOpen() {
        if (window is SinceOpen) state.reset()
    }

    class Bag {
        private val list: MutableList<AggregateBinding> = mutableListOf()

        internal fun add(binding: AggregateBinding) {
            list.add(binding)
        }

        fun all(): List<AggregateBinding> = list

        fun bindingsForSymbol(symbol: String): List<AggregateBinding> = list.filter { it.ruleSymbol == symbol }

        companion object {
            fun stateFor(
                fn: AggFn,
                window: Window,
            ): AggregateState =
                when (window) {
                    SinceOpen -> AggregateState.sinceOpen(fn)
                    is SinceTPast -> AggregateState.sinceT(fn, window.n)
                }
        }
    }
}
