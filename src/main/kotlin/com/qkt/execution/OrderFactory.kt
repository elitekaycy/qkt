package com.qkt.execution

import com.qkt.common.Side
import com.qkt.strategy.Signal

fun Signal.toOrderRequest(
    id: String,
    ts: Long,
    timeInForce: TimeInForce = TimeInForce.GTC,
): OrderRequest =
    when (this) {
        is Signal.Buy ->
            OrderRequest.Market(
                id = id,
                symbol = symbol,
                side = Side.BUY,
                quantity = size,
                timeInForce = timeInForce,
                timestamp = ts,
            )
        is Signal.Sell ->
            OrderRequest.Market(
                id = id,
                symbol = symbol,
                side = Side.SELL,
                quantity = size,
                timeInForce = timeInForce,
                timestamp = ts,
            )
        is Signal.Submit -> request
    }
