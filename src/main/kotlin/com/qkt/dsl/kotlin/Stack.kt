package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing

val entryPrice: ExprAst = StackEntryRef

fun stack(
    count: Int,
    spacing: ExprAst,
    direction: StackDirection = StackDirection.TRADE_DIRECTION,
    within: DurationAst? = null,
): StackAst = StackSpacing(count, spacing, direction, within)

fun stackOf(
    vararg layers: StackLayer,
    within: DurationAst? = null,
): StackAst {
    require(layers.isNotEmpty()) { "stackOf must have at least one layer" }
    layers.forEachIndexed { i, l ->
        require(i == 0 || l.at != null) { "layer $i must have an AT clause" }
    }
    return StackLayers(layers.toList(), within)
}

fun layer(
    qty: ExprAst,
    orderType: OrderTypeAst? = null,
    at: ExprAst? = null,
): StackLayer = StackLayer(SizeQty(qty), orderType, at)

fun layer(
    sizing: SizingAst,
    orderType: OrderTypeAst? = null,
    at: ExprAst? = null,
): StackLayer = StackLayer(sizing, orderType, at)

fun duration(text: String): DurationAst {
    require(text.length >= 2) { "invalid duration '$text'" }
    val unit = text.last()
    val n = text.dropLast(1).toLongOrNull() ?: error("invalid duration '$text'")
    val millis =
        when (unit) {
            's' -> n * 1_000L
            'm' -> n * 60_000L
            'h' -> n * 3_600_000L
            'd' -> n * 86_400_000L
            else -> error("unknown duration unit '$unit' in '$text'")
        }
    return DurationAst(millis)
}
