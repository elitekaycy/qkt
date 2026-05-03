package com.qkt.execution

import com.qkt.common.Side
import com.qkt.strategy.Signal

fun Signal.toOrder(
    id: String,
    ts: Long,
): Order =
    when (this) {
        is Signal.Buy -> Order(id, symbol, Side.BUY, size, OrderType.MARKET, null, ts)
        is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
    }
