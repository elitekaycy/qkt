package com.qkt.app.order

import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/**
 * The exit order that implements a bracket's [spec]: a plain stop at a fixed price, or an
 * engine-managed stop (armed trail, stepped, time-tightening) that moves from [entryPrice].
 * [exitSide] is the protective side — SELL for a long entry. [entryPrice] is ignored for a
 * fixed stop and required for every managed one.
 */
internal fun protectiveStop(
    id: String,
    spec: StopLossSpec,
    symbol: String,
    exitSide: Side,
    quantity: BigDecimal,
    entryPrice: BigDecimal?,
    timeInForce: TimeInForce,
    timestamp: Long,
    strategyId: String,
    legIntent: LegIntent,
): OrderRequest =
    when (spec) {
        is StopLossSpec.Fixed ->
            OrderRequest.Stop(
                id = id,
                symbol = symbol,
                side = exitSide,
                quantity = quantity,
                stopPrice = spec.price,
                timeInForce = timeInForce,
                timestamp = timestamp,
                strategyId = strategyId,
                legIntent = legIntent,
            )
        is StopLossSpec.ArmedTrail ->
            OrderRequest.ArmedTrailingStop(
                id = id,
                symbol = symbol,
                side = exitSide,
                quantity = quantity,
                entryPrice = requireEntry(entryPrice, id),
                trailDistance = spec.trailDistance,
                mfeThreshold = spec.mfeThreshold,
                timeInForce = timeInForce,
                timestamp = timestamp,
                strategyId = strategyId,
                legIntent = legIntent,
            )
        is StopLossSpec.SteppedStop ->
            OrderRequest.SteppedStop(
                id = id,
                symbol = symbol,
                side = exitSide,
                quantity = quantity,
                entryPrice = requireEntry(entryPrice, id),
                initialDistance = spec.initialDistance,
                steps = spec.steps,
                timeInForce = timeInForce,
                timestamp = timestamp,
                strategyId = strategyId,
                legIntent = legIntent,
            )
        is StopLossSpec.TimeTighten ->
            OrderRequest.TimeTighteningStop(
                id = id,
                symbol = symbol,
                side = exitSide,
                quantity = quantity,
                entryPrice = requireEntry(entryPrice, id),
                initialDistance = spec.initialDistance,
                tightenBy = spec.tightenBy,
                intervalMs = spec.intervalMs,
                floorDistance = spec.floorDistance,
                timeInForce = timeInForce,
                timestamp = timestamp,
                strategyId = strategyId,
                legIntent = legIntent,
            )
    }

private fun requireEntry(
    entryPrice: BigDecimal?,
    id: String,
): BigDecimal = requireNotNull(entryPrice) { "engine-managed stop $id needs an entry price" }
