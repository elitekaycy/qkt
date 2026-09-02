package com.qkt.positions

import com.qkt.app.LegIntentResolver
import com.qkt.broker.PositionAccountingMode
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/**
 * Test stand-in for the order-carried leg intent. Tests declare what a client order id means;
 * fills are then routed through the production [LegIntentResolver] exactly as the pipeline
 * does — the declared order's intent first, then a leg already owning the venue ticket, then
 * the netting default.
 */
class IntentBook(
    private val mode: PositionAccountingMode = PositionAccountingMode.NETTING,
) {
    private val intents = mutableMapOf<String, LegIntent>()
    private val orders = mutableMapOf<String, OrderRequest>()

    fun stackOpen(
        strategyId: String,
        clientOrderId: String,
        stackLegId: String,
        parentLegId: String,
    ) {
        intents[clientOrderId] = LegIntent.Open(stackLegId, LegRole.STACK, parentLegId)
    }

    fun independentOpen(
        strategyId: String,
        clientOrderId: String,
        legId: String,
    ) {
        intents[clientOrderId] = LegIntent.Open(legId, LegRole.INDEPENDENT)
    }

    fun close(
        strategyId: String,
        clientOrderId: String,
        legId: String,
    ) {
        intents[clientOrderId] = LegIntent.Close(legId = legId)
    }

    fun apply(
        tracker: StrategyPositionTracker,
        event: BrokerEvent.OrderFilled,
        cumulativeFilled: BigDecimal? = null,
    ): BigDecimal = applyDetailed(tracker, event, cumulativeFilled).realized

    fun applyDetailed(
        tracker: StrategyPositionTracker,
        event: BrokerEvent.OrderFilled,
        cumulativeFilled: BigDecimal? = null,
    ): StrategyPositionTracker.FillApplication {
        // The declared order is materialized on its first execution: an entry executes on its
        // own side, which is what lets the resolver tell a venue close reported under the entry
        // id from the entry itself.
        val intent = intents[event.clientOrderId]
        if (intent != null && event.clientOrderId !in orders) {
            orders[event.clientOrderId] =
                OrderRequest.Market(
                    id = event.clientOrderId,
                    symbol = event.symbol,
                    side = event.side,
                    quantity = event.quantity,
                    timeInForce = TimeInForce.GTC,
                    timestamp = event.timestamp,
                    strategyId = event.strategyId,
                    legIntent = intent,
                )
        }
        val resolver =
            LegIntentResolver(
                orderFor = { orders[it] },
                ownedLegByTicket = { s, sym, t -> tracker.legBookFor(s, sym)?.ownedLegByTicket(t) },
                positionMode = { mode },
            )
        return tracker.applyFillDetailed(event, resolver.resolve(event).intent, cumulativeFilled)
    }
}
