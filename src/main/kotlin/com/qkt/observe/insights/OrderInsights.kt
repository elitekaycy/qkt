package com.qkt.observe.insights

import com.qkt.broker.OrderModification
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderRequestEvidence

/**
 * Insights translation for the order lifecycle: submission, venue acceptance, full and
 * partial fills, cancels, rejects and modifications. Mixed into [InsightsTranslate]; pure,
 * allocation limited to the payload map each envelope carries.
 */
interface OrderInsights {
    /**
     * Translate an order submission. [referencePrice] is the sided execution price the
     * engine saw when it submitted — ask for BUY, bid for SELL — so fills can be measured
     * against it; null when no quote was available.
     */
    fun fromOrderSubmit(
        e: OrderEvent,
        referencePrice: java.math.BigDecimal? = null,
    ): InsightsEnvelope {
        val payload = OrderRequestEvidence.payload(e.request).toMutableMap()
        payload["orderSchemaVersion"] = OrderRequestEvidence.SCHEMA_VERSION
        referencePrice?.let { payload["referencePrice"] = it }
        if (e.request is OrderRequest.Bracket) {
            payload["planOrderId"] = e.request.id
            payload["orderId"] = e.request.entry.id
        }
        return busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.request.strategyId,
            "order.submit",
            payload,
        )
    }

    fun fromOrderAccepted(e: BrokerEvent.OrderAccepted): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.accepted",
            mapOf("orderId" to e.clientOrderId, "brokerOrderId" to e.brokerOrderId.orEmpty()),
        )

    fun fromOrderFilled(e: BrokerEvent.OrderFilled): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.filled",
            mapOf(
                "orderId" to e.clientOrderId,
                "brokerOrderId" to e.brokerOrderId,
                "symbol" to e.symbol,
                "price" to e.price,
                "qty" to e.quantity,
                "venueCosts" to e.venueCosts,
                "typedVenueCosts" to venueCostsPayload(e.typedVenueCosts),
            ) + (e.exitReason?.let { mapOf("exitReason" to it.name) } ?: emptyMap()),
        )

    fun fromOrderPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.partially_filled",
            mapOf(
                "orderId" to e.clientOrderId,
                "brokerOrderId" to e.brokerOrderId,
                "symbol" to e.symbol,
                "side" to e.side.name,
                "price" to e.price,
                "qty" to e.quantity,
                "cumulativeQty" to e.cumulativeFilled,
                "venueCosts" to e.venueCosts,
                "typedVenueCosts" to venueCostsPayload(e.typedVenueCosts),
            ) + (e.exitReason?.let { mapOf("exitReason" to it.name) } ?: emptyMap()),
        )

    fun fromOrderCancelled(e: BrokerEvent.OrderCancelled): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.cancelled",
            mapOf("orderId" to e.clientOrderId, "brokerOrderId" to e.brokerOrderId, "reason" to e.reason),
        )

    fun fromOrderRejected(e: BrokerEvent.OrderRejected): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.rejected",
            mapOf("orderId" to e.clientOrderId, "brokerOrderId" to e.brokerOrderId, "reason" to e.reason),
        )

    fun fromOrderModified(e: BrokerEvent.OrderModified): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.modified",
            mapOf(
                "orderId" to e.clientOrderId,
                "brokerOrderId" to e.brokerOrderId,
                "changes" to orderModificationPayload(e.changes),
            ),
        )
}

private fun orderModificationPayload(changes: OrderModification): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "newQuantity" to changes.newQuantity,
        "newLimitPrice" to changes.newLimitPrice,
        "newStopPrice" to changes.newStopPrice,
    )

private fun venueCostsPayload(costs: List<com.qkt.accounting.VenueCost>): List<Map<String, Any?>> =
    costs.map {
        mapOf(
            "kind" to it.kind.name,
            "amount" to it.amount.amount,
            "currency" to it.amount.normalizedCurrency,
            "ts" to it.timestamp,
        )
    }
