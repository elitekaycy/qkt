package com.qkt.risk.book

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.OrderRequest

/**
 * The reservation key for one order. Every child session numbers its own orders from ORD-0, so the
 * order id alone collides across a book; the strategy id makes it unique.
 */
fun bookReservationKey(
    strategyId: String,
    orderId: String,
): String = "$strategyId\u0001$orderId"

/**
 * The reservation key for [request]: the id its fill, venue rejection or cancel reports under. A composite
 * reports under its opening leg (a bracket fills as its entry), so reserving it under its own id would leave
 * the reservation unmatched when it fills, and the filled position counted twice until the reservation ages out.
 */
fun bookReservationKey(request: OrderRequest): String = bookReservationKey(request.strategyId, request.openingLegId())

private fun OrderRequest.openingLegId(): String =
    when (this) {
        is OrderRequest.Bracket -> entry.openingLegId()
        is OrderRequest.OTO -> parent.openingLegId()
        is OrderRequest.ScaleOut -> basis.openingLegId()
        is OrderRequest.TimeExit -> target.openingLegId()
        else -> id
    }

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
        controller.release(bookReservationKey(e.request))
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
