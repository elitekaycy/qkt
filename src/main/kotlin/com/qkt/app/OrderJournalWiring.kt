package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.observe.OrderJournal
import com.qkt.strategy.Strategy
import com.qkt.strategy.targetSymbol

/**
 * Every order-lifecycle event lands in the append-only journal, in bus order, e.g. a filled
 * market buy writes `submit` (with `"approved":"true"`), `accepted`, then `filled`.
 *
 * An [com.qkt.events.OrderEvent] only exists because risk approved the request, so the
 * submit path writes ONE `"submit"` line with `"approved":"true"` instead of separate
 * `risk-approved` + `submit` lines — one durable write per submit, not two (#648).
 */
internal class OrderJournalWiring(
    private val strategies: List<Pair<String, Strategy>>,
) {
    /** Subscribe the journal's handlers on [bus]; call exactly where the session wires its journal. */
    fun wire(
        bus: EventBus,
        journal: OrderJournal,
    ) {
        fun orderFields(request: com.qkt.execution.OrderRequest): Map<String, String?> =
            mapOf(
                "id" to request.id,
                "type" to request::class.simpleName,
                "symbol" to request.symbol,
                "side" to request.side.name,
                "qty" to request.quantity.toPlainString(),
            )

        bus.subscribe<com.qkt.events.OrderEvent> { e ->
            journal.append(
                e.request.strategyId,
                "submit",
                orderFields(e.request) + ("approved" to "true"),
            )
        }
        bus.subscribe<BrokerEvent.OrderAccepted> { e ->
            journal.append(e.strategyId, "accepted", mapOf("id" to e.clientOrderId, "broker" to e.brokerOrderId))
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            journal.append(
                e.strategyId,
                "rejected",
                mapOf("id" to e.clientOrderId, "reason" to e.reason),
            )
        }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            journal.append(
                e.strategyId,
                "filled",
                mapOf(
                    "id" to e.clientOrderId,
                    "broker" to e.brokerOrderId,
                    "symbol" to e.symbol,
                    "side" to e.side.name,
                    "price" to e.price.toPlainString(),
                    "qty" to e.quantity.toPlainString(),
                    "venueCosts" to e.venueCosts.toPlainString(),
                ),
            )
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { e ->
            journal.append(
                e.strategyId,
                "cancelled",
                mapOf("id" to e.clientOrderId, "reason" to e.reason),
            )
        }
        bus.subscribe<BrokerEvent.PositionProtectionChanged> { e ->
            journal.append(
                e.strategyId.ifBlank { strategies.firstOrNull()?.first.orEmpty() },
                "position-protection-changed",
                mapOf(
                    "broker" to e.broker,
                    "symbol" to e.symbol,
                    "ticket" to e.ticket,
                    "oldSl" to e.oldStopLoss.toPlainString(),
                    "newSl" to e.newStopLoss.toPlainString(),
                    "oldTp" to e.oldTakeProfit.toPlainString(),
                    "newTp" to e.newTakeProfit.toPlainString(),
                ),
            )
        }
        bus.subscribe<com.qkt.events.RiskRejectedEvent> { e ->
            journal.append(
                e.request.strategyId,
                "risk-rejected",
                mapOf("id" to e.request.id, "symbol" to e.request.symbol, "reason" to e.reason),
            )
        }
        bus.subscribe<com.qkt.events.SignalSuppressedEvent> { e ->
            journal.append(
                e.strategyId,
                "signal-suppressed",
                mapOf("symbol" to e.signal.targetSymbol(), "reason" to e.reason),
            )
        }
        bus.subscribe<RiskEvent.Halted> { e ->
            journal.append(e.strategyId.orEmpty(), "halted", mapOf("reason" to e.reason))
        }
        bus.subscribe<RiskEvent.Resumed> { e ->
            journal.append(e.strategyId.orEmpty(), "resumed", emptyMap<String, String?>())
        }
    }
}
