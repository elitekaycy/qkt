package com.qkt.execution

import com.qkt.common.Side
import com.qkt.strategy.Signal

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
