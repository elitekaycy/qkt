package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.Window

class AggregateBinding(
    val seriesEvaluator: CompiledExpr,
    val window: Window,
    val state: AggregateState,
    val ruleAlias: String,
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

        // Grouped view built lazily after binding completes — bindingsForAlias runs several
        // times per bar close, and filtering the flat list allocated a fresh list each call.
        private var byAlias: Map<String, List<AggregateBinding>>? = null

        internal fun add(binding: AggregateBinding) {
            list.add(binding)
            byAlias = null
        }

        fun all(): List<AggregateBinding> = list

        fun bindingsForAlias(alias: String): List<AggregateBinding> {
            val grouped = byAlias ?: list.groupBy { it.ruleAlias }.also { byAlias = it }
            return grouped[alias] ?: emptyList()
        }

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
