package com.qkt.app.order

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.compile.BracketPercent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal

// Price math for exits that are anchored to an entry fill: bracket children written as
// BY / AT / PCT / RR distances and stack layer triggers written relative to `entry`. All of
// it is pure — the anchor is the fill price, and nothing here reads order state.

/** Evaluates a stack/bracket price expression, substituting [anchor] for `entry`. */
internal fun evaluateAt(
    expr: ExprAst,
    anchor: BigDecimal,
): BigDecimal =
    when (expr) {
        is StackEntryRef -> anchor
        is NumLit -> expr.value
        is BinaryOp -> {
            val l = evaluateAt(expr.lhs, anchor)
            val r = evaluateAt(expr.rhs, anchor)
            when (expr.op) {
                BinOp.ADD -> l + r
                BinOp.SUB -> l - r
                BinOp.MUL -> l * r
                BinOp.DIV -> l.divide(r, Money.CONTEXT)
                else -> error("unsupported op in stack trigger: ${expr.op}")
            }
        }

        else -> error("unsupported trigger expression: $expr")
    }

/** True when [expr] mentions `entry`, which only exists once a stack's first layer fills. */
internal fun referencesStackEntryRef(expr: ExprAst): Boolean =
    when (expr) {
        is StackEntryRef -> true
        is BinaryOp -> referencesStackEntryRef(expr.lhs) || referencesStackEntryRef(expr.rhs)
        else -> false
    }

/**
 * Absolute price of a bracket child for an entry on [side] filled at [fillPrice]. A stop sits
 * against the entry's direction and a target with it; an RR target needs the stop's distance.
 */
internal fun computeChildPrice(
    childPrice: ChildPriceAst,
    side: Side,
    fillPrice: BigDecimal,
    isStopLoss: Boolean,
    slDistance: BigDecimal? = null,
): BigDecimal {
    val sign =
        if (side == Side.BUY) {
            if (isStopLoss) BigDecimal("-1") else BigDecimal("1")
        } else {
            if (isStopLoss) BigDecimal("1") else BigDecimal("-1")
        }
    return when (childPrice) {
        is ChildBy -> {
            val distance = evaluateAt(childPrice.distance, fillPrice)
            (fillPrice + distance.multiply(sign)).setScale(Money.SCALE, Money.ROUNDING)
        }
        is ChildAt -> evaluateAt(childPrice.price, fillPrice).setScale(Money.SCALE, Money.ROUNDING)
        is ChildPct -> {
            val percent = evaluateAt(childPrice.percent, fillPrice)
            val fraction = BracketPercent.fraction(percent, isStopLoss)
            val distance = fillPrice.multiply(fraction, Money.CONTEXT)
            (fillPrice + distance.multiply(sign)).setScale(Money.SCALE, Money.ROUNDING)
        }
        is ChildRr -> {
            require(!isStopLoss) { "RR is only valid for TAKE PROFIT, not STOP LOSS" }
            val sl =
                slDistance
                    ?: error("ChildRr requires a resolvable STOP LOSS distance from outerBracket")
            val multiplier = evaluateAt(childPrice.multiplier, fillPrice)
            val distance = sl.multiply(multiplier, Money.CONTEXT)
            (fillPrice + distance.multiply(sign)).setScale(Money.SCALE, Money.ROUNDING)
        }
        is ChildArmedTrail -> {
            require(isStopLoss) { "ChildArmedTrail is only valid for STOP LOSS, not TAKE PROFIT" }
            // Pre-arm stop level: `fillPrice ± trailDistance`. The armed/trailing
            // behaviour is gated separately via [StopLossSpec.ArmedTrail] in OrderManager's
            // tick loop; this path computes the static pre-arm level only.
            val distance = evaluateAt(childPrice.trailDistance, fillPrice)
            (fillPrice + distance.multiply(sign)).setScale(Money.SCALE, Money.ROUNDING)
        }
    }
}

/** Re-anchors [req]'s expression-written stop and target on the actual [fillPrice]. */
internal fun resolveBracketAtFill(
    req: OrderRequest.Bracket,
    fillPrice: BigDecimal,
): OrderRequest.Bracket {
    val stop =
        req.stopLossAst?.let { ast ->
            when (ast) {
                is ChildArmedTrail ->
                    StopLossSpec.ArmedTrail(
                        evaluateAt(ast.trailDistance, fillPrice),
                        evaluateAt(ast.mfeThreshold, fillPrice),
                    )
                is ChildBy ->
                    if (req.stopLoss !is StopLossSpec.Fixed) {
                        req.stopLoss
                    } else {
                        StopLossSpec.Fixed(
                            computeChildPrice(ast, req.side, fillPrice, isStopLoss = true),
                        )
                    }
                else ->
                    StopLossSpec.Fixed(
                        computeChildPrice(ast, req.side, fillPrice, isStopLoss = true),
                    )
            }
        } ?: req.stopLoss
    val stopDistance =
        when (stop) {
            is StopLossSpec.Fixed -> (fillPrice - stop.price).abs()
            is StopLossSpec.ArmedTrail -> stop.trailDistance
            is StopLossSpec.SteppedStop -> stop.initialDistance
            is StopLossSpec.TimeTighten -> stop.initialDistance
        }
    val takeProfit =
        req.takeProfitAst?.let {
            computeChildPrice(it, req.side, fillPrice, isStopLoss = false, slDistance = stopDistance)
        } ?: req.takeProfit
    return req.copy(takeProfit = takeProfit, stopLoss = stop)
}

/** The stop price [bracket] starts at when its entry fills at [entryPrice], before any trailing. */
internal fun stopPriceAtEntry(
    bracket: OrderRequest.Bracket,
    entryPrice: BigDecimal,
): BigDecimal =
    when (val stop = bracket.stopLoss) {
        is StopLossSpec.Fixed -> stop.price
        is StopLossSpec.ArmedTrail ->
            if (bracket.side == Side.BUY) entryPrice - stop.trailDistance else entryPrice + stop.trailDistance
        is StopLossSpec.SteppedStop ->
            if (bracket.side == Side.BUY) entryPrice - stop.initialDistance else entryPrice + stop.initialDistance
        is StopLossSpec.TimeTighten ->
            if (bracket.side == Side.BUY) entryPrice - stop.initialDistance else entryPrice + stop.initialDistance
    }
