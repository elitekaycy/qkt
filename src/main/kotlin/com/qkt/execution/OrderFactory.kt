package com.qkt.execution

import com.qkt.common.Side
import com.qkt.strategy.Signal

/**
 * Translates a [Signal] into an [OrderRequest], or returns `null` if the signal is
 * informational (cancel-pending) and doesn't produce a new order.
 *
 * `Signal.Submit` passes its embedded request through with [strategyId] stamped on it;
 * `Signal.Buy`/`Sell` become `OrderRequest.Market`; `Signal.CancelPendingForSymbol`
 * returns `null` because the order manager handles it via a side-channel.
 */
fun Signal.toOrderRequest(
    id: String,
    ts: Long,
    timeInForce: TimeInForce = TimeInForce.GTC,
    strategyId: String = "",
): OrderRequest? =
    when (this) {
        is Signal.Buy ->
            OrderRequest.Market(
                id = id,
                symbol = symbol,
                side = Side.BUY,
                quantity = size,
                timeInForce = timeInForce,
                timestamp = ts,
                strategyId = strategyId,
            )
        is Signal.Sell ->
            OrderRequest.Market(
                id = id,
                symbol = symbol,
                side = Side.SELL,
                quantity = size,
                timeInForce = timeInForce,
                timestamp = ts,
                strategyId = strategyId,
            )
        is Signal.Submit -> request.withStrategyId(strategyId)
        is Signal.CancelPendingForSymbol -> null
    }
