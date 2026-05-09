package com.qkt.dsl.ast

sealed interface StackAst

data class StackSpacing(
    val count: Int,
    val spacing: ExprAst,
    val direction: StackDirection,
    val within: DurationAst? = null,
) : StackAst {
    init {
        require(count >= 1) { "STACK count must be >= 1: $count" }
    }
}

data class StackLayers(
    val layers: List<StackLayer>,
    val within: DurationAst? = null,
) : StackAst {
    init {
        require(layers.isNotEmpty()) { "STACK layer list must not be empty" }
    }
}

enum class StackDirection { TRADE_DIRECTION, ABOVE, BELOW }

data class StackLayer(
    val sizing: SizingAst,
    val orderType: OrderTypeAst? = null,
    val at: ExprAst? = null,
)

data class DurationAst(
    val millis: Long,
) {
    init {
        require(millis > 0) { "DURATION must be > 0 ms: $millis" }
    }
}
