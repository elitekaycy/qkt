package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.Side
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.strategy.OrderOutcome
import java.math.BigDecimal

/**
 * Hands each DSL strategy the outcomes of its own orders ([DslCompiledStrategy.onOrderOutcome]): fills
 * and partial fills with their price, quantity and exit reason, and rejections and cancels with their
 * reason. An engine-held exit (a bracket's stop or target) carries the kind its leg implies and its
 * parent's id. Subscribed last, so the books already hold what the outcome reports.
 */
internal fun wireOrderOutcomeNotices(
    bus: EventBus,
    strategies: Map<String, DslCompiledStrategy>,
    ownerOf: (BrokerEvent.OrderEvent) -> String,
    orders: (String) -> ManagedOrder?,
) {
    fun filled(
        e: BrokerEvent.OrderEvent,
        fill: Fill,
    ) {
        val strategy = strategies[ownerOf(e)] ?: return
        val order = orders(e.clientOrderId)
        val parent = order?.parentClientOrderId?.let(orders)
        val reason = fill.venueReason ?: order?.let { engineExit(it.request, parent?.request) }
        strategy.onOrderOutcome(
            OrderOutcome.Filled(
                orderId = e.clientOrderId,
                symbol = fill.symbol,
                side = fill.side,
                price = fill.price,
                quantity = fill.quantity,
                partial = fill.partial,
                exitReason = reason,
                timestamp = e.timestamp,
                parentOrderId = parent?.id,
            ),
        )
    }
    bus.subscribe<BrokerEvent.OrderFilled> { e ->
        filled(e, Fill(e.symbol, e.side, e.price, e.quantity, false, e.exitReason))
    }
    bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e ->
        filled(e, Fill(e.symbol, e.side, e.price, e.quantity, true, e.exitReason))
    }
    bus.subscribe<BrokerEvent.OrderRejected> { e ->
        strategies[
            ownerOf(
                e,
            ),
        ]?.onOrderOutcome(OrderOutcome.Rejected(e.clientOrderId, "venue: ${e.reason}", e.timestamp))
    }
    bus.subscribe<BrokerEvent.OrderCancelled> { e ->
        strategies[ownerOf(e)]?.onOrderOutcome(OrderOutcome.Cancelled(e.clientOrderId, e.reason, e.timestamp))
    }
    bus.subscribe<RiskRejectedEvent> { e ->
        strategies[e.request.strategyId]?.onOrderOutcome(OrderOutcome.Rejected(e.request.id, e.reason, e.timestamp))
    }
}

private class Fill(
    val symbol: String,
    val side: Side,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val partial: Boolean,
    val venueReason: ExitReason?,
)

/**
 * An engine-held exit's kind: a leg of a bracket's exit pair (the OCO it hangs under) is a stop or a
 * target by its type; any other leg that closes a position is a close.
 */
private fun engineExit(
    leg: OrderRequest,
    parent: OrderRequest?,
): ExitReason? {
    val exitPair = parent is OrderRequest.StandaloneOCO || leg.legIntent is com.qkt.execution.LegIntent.Close
    return when {
        !exitPair -> null
        leg is OrderRequest.Stop -> ExitReason.STOP
        leg is OrderRequest.Limit -> ExitReason.TAKE_PROFIT
        else -> ExitReason.CLOSE
    }
}
