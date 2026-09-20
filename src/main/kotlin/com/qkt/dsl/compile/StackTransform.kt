package com.qkt.dsl.compile

import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing

/**
 * The [ExprTransform] rewrite of STACK specs and STACK AT tiers: spacing, each layer's sizing,
 * order type and price, and each tier's threshold, sizing, bracket and recover distance.
 */
internal class StackTransform(
    private val exprs: ExprTransform,
) {
    fun stack(s: StackAst): StackAst =
        when (s) {
            is StackSpacing -> s.copy(spacing = exprs.expr(s.spacing))
            is StackLayers -> s.copy(layers = s.layers.map(::stackLayer))
        }

    fun stackLayer(l: StackLayer): StackLayer =
        StackLayer(exprs.sizing(l.sizing), l.orderType?.let(exprs::orderType), l.at?.let(exprs::expr))

    fun stackAt(c: StackAtClause): StackAtClause =
        StackAtClause(
            exprs.expr(c.mfeThreshold),
            c.withinDuration,
            exprs.sizing(c.sizing),
            exprs.bracket(c.bracket),
            c.maeRecoverDistance?.let(exprs::expr),
        )
}
