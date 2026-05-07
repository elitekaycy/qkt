package com.qkt.dsl.compile

import com.qkt.strategy.Signal

class CompiledRule(
    private val condition: CompiledExpr,
    private val action: (EvalContext) -> Signal,
) {
    fun fire(ctx: EvalContext): Signal? {
        val v = condition.evaluate(ctx)
        if (v is Value.Bool && v.v) return action(ctx)
        return null
    }
}
