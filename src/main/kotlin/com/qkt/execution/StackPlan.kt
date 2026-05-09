package com.qkt.execution

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackDirection

data class StackPlan(
    val layers: List<LayerSpec>,
    val outerBracket: BracketAst? = null,
    val withinMillis: Long? = null,
) {
    init {
        require(layers.isNotEmpty()) { "StackPlan must have at least one layer" }
    }
}

data class LayerSpec(
    val index: Int,
    val sizing: SizingAst,
    val orderType: OrderTypeAst,
    val trigger: LayerTrigger,
)

sealed interface LayerTrigger

data object Immediate : LayerTrigger

data class At(
    val price: ExprAst,
    val direction: StackDirection,
) : LayerTrigger
