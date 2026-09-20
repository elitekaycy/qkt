package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.NumLit
import java.math.BigDecimal

/** A bracket's stop and take-profit resolved at fire time, with the stop distance risk sizing uses. */
internal data class ResolvedBracket(
    val stopLoss: com.qkt.execution.StopLossSpec,
    val stopDistance: BigDecimal,
    val takeProfit: BigDecimal,
)

/**
 * Resolves a `BRACKET`'s stop and take-profit against [entry] at fire time. Returns null (after a
 * once-only warm-up log) when either price is still undefined, so the order is skipped.
 */
internal fun resolveBracket(
    compiledSL: CompiledStopLoss?,
    compiledTP: CompiledChildPrice?,
    ctx: EvalContext,
    side: Side,
    entry: BigDecimal,
    skipLog: WarmupSkipLog,
    stream: String,
): ResolvedBracket? {
    val sl = requireNotNull(compiledSL) { "BRACKET requires STOP LOSS" }
    val tp = requireNotNull(compiledTP) { "BRACKET requires TAKE PROFIT" }
    val slSpec =
        resolveStopLoss(sl, ctx, side, entry)
            ?: run {
                skipLog.skipped("bracket stop price", ctx, stream)
                return null
            }
    val stopDistance = stopDistance(entry, slSpec)
    val takeProfit =
        tp.evaluate(ctx, side, entry, stopDistance = stopDistance)
            ?: run {
                skipLog.skipped("bracket take-profit price", ctx, stream)
                return null
            }
    return ResolvedBracket(slSpec, stopDistance, takeProfit)
}

/** Resolves a compiled stop loss against [entry]; null while a dynamic stop's inputs are warming up. */
internal fun resolveStopLoss(
    compiled: CompiledStopLoss,
    ctx: EvalContext,
    side: Side,
    entry: BigDecimal,
): com.qkt.execution.StopLossSpec? =
    when (compiled) {
        is CompiledStopLoss.Static -> compiled.spec
        is CompiledStopLoss.Dynamic -> compiled.evaluate(ctx, side, entry)
    }

/** The worst-case distance between [entry] and the stop, the quantity `SIZING RISK` divides by. */
internal fun stopDistance(
    entry: BigDecimal,
    stopLoss: com.qkt.execution.StopLossSpec,
): BigDecimal =
    when (stopLoss) {
        is com.qkt.execution.StopLossSpec.Fixed -> entry.subtract(stopLoss.price).abs()
        is com.qkt.execution.StopLossSpec.ArmedTrail -> stopLoss.trailDistance
        is com.qkt.execution.StopLossSpec.SteppedStop -> stopLoss.initialDistance
        is com.qkt.execution.StopLossSpec.TimeTighten -> stopLoss.initialDistance
    }

/** The stop distance known at compile time (a literal `BY` or armed-trail distance), else null. */
internal fun resolveStaticStopDistance(stop: ChildPriceAst?): BigDecimal? =
    when (stop) {
        is ChildBy -> {
            val expr = stop.distance
            if (expr is NumLit) expr.value else null
        }
        is com.qkt.dsl.ast.ChildArmedTrail -> {
            // Armed trail's trail distance IS the worst-case stop distance for risk
            // sizing — pre-arm the stop sits at entry ± distance, post-arm the stop
            // trails by the same distance. Either way `SIZING RISK $ N` sees a
            // well-defined stop distance. See spec §6 and plan correction 5.
            val expr = stop.trailDistance
            if (expr is NumLit) expr.value else null
        }
        else -> null
    }
