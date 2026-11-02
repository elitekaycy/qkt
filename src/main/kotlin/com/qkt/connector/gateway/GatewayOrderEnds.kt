package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled

/** The engine event of an order [order] that the venue cancelled or rejected. */
internal fun ended(
    order: WireOrder,
    strategy: String,
): BrokerEvent.OrderEvent =
    if (order.status == "rejected") {
        BrokerEvent.OrderRejected(
            order.clientOrderId,
            order.venueOrderId,
            order.rejectReason ?: "rejected by the venue",
            strategy,
        )
    } else {
        BrokerEvent.OrderCancelled(order.clientOrderId, order.venueOrderId, "cancelled at the venue", strategy)
    }

/**
 * The end of an order the venue says filled short of its quantity, every fill already heard: what filled stands and
 * the rest is gone, as for a cancel, e.g. `filled at the venue: 0.0001995 of 0.0002`.
 */
internal fun filledShort(
    order: WireOrder,
    strategy: String,
): BrokerEvent.OrderEvent =
    BrokerEvent.OrderCancelled(
        order.clientOrderId,
        order.venueOrderId,
        "filled at the venue: ${order.filledQuantity} of ${order.quantity}",
        strategy,
    )

/** The engine event a `settlement` means: the contract settled at its price, for every holder. */
internal fun settledEvent(
    settlement: WireSettlement,
    symbols: GatewaySymbols,
): ContractSettled =
    ContractSettled(
        symbols.qkt(settlement.symbol),
        WireValues.decimal(settlement.price, "price"),
        WireValues.costsOf(settlement.costs, settlement.time),
        settlement.time,
    )
