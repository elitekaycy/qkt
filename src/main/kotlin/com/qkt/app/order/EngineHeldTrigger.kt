package com.qkt.app.order

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.TrailMode
import com.qkt.marketdata.Tick
import com.qkt.marketdata.buyExecPrice
import com.qkt.marketdata.sellExecPrice
import java.math.BigDecimal

/**
 * True when [tick] reaches the trigger of the engine-held order [managed]. A moving stop reads its
 * current level from [stops]; one without a reference price yet never triggers.
 *
 * Side-aware like the venue: a BUY executes at the ask, a SELL at the bid, so the engine-held
 * trigger compares against the side's execution price — otherwise engine-held triggers fire on
 * mid while native venue triggers fire on bid/ask, and live is internally inconsistent (#382).
 */
internal fun isTriggered(
    managed: ManagedOrder,
    tick: Tick,
    stops: ManagedStopBook,
): Boolean {
    val request = managed.request
    val exec = if (request.side == Side.BUY) tick.buyExecPrice() else tick.sellExecPrice()
    return when (request) {
        is OrderRequest.Stop ->
            if (request.side == Side.BUY) exec >= request.stopPrice else exec <= request.stopPrice
        is OrderRequest.StopLimit ->
            if (request.side == Side.BUY) exec >= request.stopPrice else exec <= request.stopPrice
        is OrderRequest.IfTouched ->
            if (request.side == Side.BUY) exec <= request.triggerPrice else exec >= request.triggerPrice
        is OrderRequest.TrailingStop,
        is OrderRequest.TrailingStopLimit,
        is OrderRequest.ArmedTrailingStop,
        is OrderRequest.SteppedStop,
        is OrderRequest.TimeTighteningStop,
        -> {
            // An exit SELL fires when price falls to the stop, an exit BUY when it rises to it.
            val current = stops.trailLevel(managed) ?: return false
            if (request.side == Side.SELL) exec <= current else exec >= current
        }
        else -> false
    }
}

/** Level of a plain trailing stop that trails [hwm] by [trailAmount] points or percent. */
internal fun trailingLevel(
    side: Side,
    hwm: BigDecimal,
    trailAmount: BigDecimal,
    trailMode: TrailMode,
): BigDecimal =
    when (trailMode) {
        TrailMode.ABSOLUTE -> if (side == Side.SELL) hwm - trailAmount else hwm + trailAmount
        TrailMode.PERCENT -> {
            val factor = trailAmount.divide(BigDecimal("100"), Money.CONTEXT)
            if (side == Side.SELL) {
                hwm
                    .multiply(BigDecimal.ONE - factor, Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            } else {
                hwm
                    .multiply(BigDecimal.ONE + factor, Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            }
        }
    }

/** Stops whose level the engine moves (armed trail, stepped, time-tightening). */
internal fun isPersistentManagedStop(request: OrderRequest): Boolean =
    request is OrderRequest.ArmedTrailingStop ||
        request is OrderRequest.SteppedStop ||
        request is OrderRequest.TimeTighteningStop

/** Engine-held orders whose moving state must be persisted to survive a restart. */
internal fun hasPersistentDynamicState(request: OrderRequest): Boolean =
    request is OrderRequest.TrailingStop ||
        request is OrderRequest.TrailingStopLimit ||
        isPersistentManagedStop(request)
