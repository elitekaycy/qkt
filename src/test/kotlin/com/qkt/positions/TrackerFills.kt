package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent

/** A fill whose venue ticket is its client order id, as the leg-ownership tests book it. */
internal fun trackerFill(
    strategyId: String,
    clientOrderId: String,
    symbol: String,
    side: Side,
    qty: String,
    price: String,
    timestamp: Long = 0L,
) = BrokerEvent.OrderFilled(
    clientOrderId = clientOrderId,
    brokerOrderId = clientOrderId,
    symbol = symbol,
    side = side,
    price = Money.of(price),
    quantity = Money.of(qty),
    strategyId = strategyId,
    timestamp = timestamp,
)
