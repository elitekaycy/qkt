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

/** What resolving a `BRACKET` at fire time produced. */
internal sealed interface BracketResolution {
    /** Both legs priced. */
    data class Resolved(
        val bracket: ResolvedBracket,
    ) : BracketResolution

    /** A leg is still undefined (warm-up): the order is skipped quietly, after a once-only log. */
    object Undefined : BracketResolution

    /** The bracket cannot protect the order: it is refused, and [reason] travels as a suppressed signal. */
    data class Refused(
        val reason: String,
    ) : BracketResolution
}

/**
 * Resolves a `BRACKET`'s stop and take-profit against [entry] at fire time. A stop that resolves
 * onto the entry price (or beyond it) leaves no stop distance: there is no risk for `SIZING RISK`
 * to divide by and a reward-to-risk target would sit on the entry itself, so the order is
 * [BracketResolution.Refused] with the levels in the reason rather than sent unprotected.
 */
internal fun resolveBracket(
    compiledSL: CompiledStopLoss?,
    compiledTP: CompiledChildPrice?,
    ctx: EvalContext,
    side: Side,
    entry: BigDecimal,
    skipLog: WarmupSkipLog,
    stream: String,
): BracketResolution {
    val sl = requireNotNull(compiledSL) { "BRACKET requires STOP LOSS" }
    val tp = requireNotNull(compiledTP) { "BRACKET requires TAKE PROFIT" }
    val slSpec =
        resolveStopLoss(sl, ctx, side, entry)
            ?: run {
                skipLog.skipped("bracket stop price", ctx, stream)
                return BracketResolution.Undefined
            }
    val stopDistance = stopDistance(entry, slSpec)
    if (stopDistance.signum() <= 0) return BracketResolution.Refused(zeroStopDistanceReason(entry, stopDistance))
    val takeProfit =
        tp.evaluate(ctx, side, entry, stopDistance = stopDistance)
            ?: run {
                skipLog.skipped("bracket take-profit price", ctx, stream)
                return BracketResolution.Undefined
            }
    return BracketResolution.Resolved(ResolvedBracket(slSpec, stopDistance, takeProfit))
}

/** The reason an order whose bracket stop resolved onto its [entry] is refused. */
internal fun zeroStopDistanceReason(
    entry: BigDecimal,
    stopDistance: BigDecimal,
): String =
    "bracket stop distance is ${stopDistance.toPlainString()} at entry ${entry.toPlainString()}: the stop sits on " +
        "the entry price, so the order has no risk to size against and no reward-to-risk target"

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
