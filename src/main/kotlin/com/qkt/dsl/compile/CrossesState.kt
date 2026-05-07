package com.qkt.dsl.compile

import com.qkt.dsl.ast.CrossDir

class CrossesState {
    private var prevLhsAboveRhs: Boolean? = null

    fun update(
        currentAbove: Boolean,
        direction: CrossDir,
    ): Value {
        val prev = prevLhsAboveRhs
        prevLhsAboveRhs = currentAbove
        if (prev == null) return Value.Undefined
        return Value.Bool(
            when (direction) {
                CrossDir.ABOVE -> !prev && currentAbove
                CrossDir.BELOW -> prev && !currentAbove
            },
        )
    }
}
