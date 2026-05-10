package com.qkt.execution

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackDirection
import java.math.BigDecimal

/**
 * Pyramiding plan for [OrderRequest.Stack].
 *
 * `layers[0]` is the seed (fires at signal time as a market or limit). Subsequent layers
 * carry [LayerTrigger]s that fire when their price condition prints. The optional
 * [withinMillis] time fence abandons unfired layers after the deadline.
 */
data class StackPlan(
    val layers: List<LayerSpec>,
    val outerBracket: BracketAst? = null,
    val withinMillis: Long? = null,
) {
    init {
        require(layers.isNotEmpty()) { "StackPlan must have at least one layer" }
    }
}

/**
 * One layer in a [StackPlan].
 *
 * `resolvedQuantity` is populated at action-execute time by the action compiler for
 * percent/risk sizing. Manually-built specs (e.g. in tests) can leave it null and rely
 * on the order manager's literal-quantity fallback.
 */
data class LayerSpec(
    val index: Int,
    val sizing: SizingAst,
    val orderType: OrderTypeAst,
    val trigger: LayerTrigger,
    val resolvedQuantity: BigDecimal? = null,
)

/** Trigger condition that decides when a [LayerSpec] becomes a live order. */
sealed interface LayerTrigger

/** Layer fires immediately on signal — the seed layer of any stack. */
data object Immediate : LayerTrigger

/** Layer fires when [price] is touched in [direction]. */
data class At(
    val price: ExprAst,
    val direction: StackDirection,
) : LayerTrigger
