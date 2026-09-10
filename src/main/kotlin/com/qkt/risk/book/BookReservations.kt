package com.qkt.risk.book

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent

/**
 * The reservation key for one order. Every child session numbers its own orders from ORD-0, so the
 * order id alone collides across a book; the strategy id makes it unique.
 */
fun bookReservationKey(
    strategyId: String,
    orderId: String,
): String = "$strategyId\u0001$orderId"

/**
 * Release or age [controller]'s reservations from one engine's own order lifecycle.
 *
 * Register AFTER the trading pipeline: the bus dispatches in registration order and the pipeline
 * folds a fill into positions before anything later sees it, so an order is only marked filled once
 * its position exists. Used identically by the live session and the backtest replay engine, so the
 * two cannot drift apart on how an approved order is counted.
 */
fun wireBookReservations(
    bus: EventBus,
    controller: BookRiskController,
) {
    // A later risk rule, or book de-risk suppression, refused an order this rule had reserved.
    bus.subscribe<RiskRejectedEvent> { e ->
        controller.release(bookReservationKey(e.request.strategyId, e.request.id))
    }
    bus.subscribe<BrokerEvent.OrderRejected> { e ->
        controller.release(bookReservationKey(e.strategyId, e.clientOrderId))
    }
    bus.subscribe<BrokerEvent.OrderCancelled> { e ->
        controller.release(bookReservationKey(e.strategyId, e.clientOrderId))
    }
    bus.subscribe<BrokerEvent.OrderFilled> { e ->
        controller.markFilled(bookReservationKey(e.strategyId, e.clientOrderId))
    }
}
