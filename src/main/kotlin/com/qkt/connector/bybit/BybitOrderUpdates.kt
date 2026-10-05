package com.qkt.connector.bybit

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Applies Bybit order records for one broker: entries of the private `order` stream, and the
 * `/v5/order/realtime` or `/v5/order/history` items a restart reads (the same shape,
 * https://bybit-exchange.github.io/docs/v5/websocket/private/order). `New` is accepted; `Cancelled`,
 * spot's `PartiallyFilledCanceled` and a conditional order's `Deactivated` end the order through [ends],
 * after the executions they report (`cumExecQty`); `Filled` ends the order with its fills alone; `Rejected`
 * is a rejection.
 */
class BybitOrderUpdates(
    private val ends: BybitHeldEnds,
    private val bus: EventBus,
    private val clock: Clock,
) {
    /** Handles one private `order` [frame]. */
    fun onFrame(frame: JsonObject) {
        frame["data"]?.jsonArray?.forEach { apply(it.jsonObject) }
    }

    /** Applies one order [record]. */
    fun apply(record: JsonObject) {
        val id = record["orderLinkId"]?.jsonPrimitive?.content ?: return
        val venueId = record["orderId"]?.jsonPrimitive?.content
        val strategyId = ends.orders.strategyOf(id).orEmpty()
        val now = clock.now()
        when (record["orderStatus"]?.jsonPrimitive?.content) {
            "New" -> bus.publish(BrokerEvent.OrderAccepted(id, venueId, strategyId, now))
            "Cancelled", "PartiallyFilledCanceled", "Deactivated" ->
                ends.end(
                    BrokerEvent.OrderCancelled(id, venueId, "venue cancel", strategyId, now),
                    record["cumExecQty"]?.jsonPrimitive?.content?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                )
            "Filled" -> ends.orders.end(id)
            "Rejected" -> {
                val reason = record["rejectReason"]?.jsonPrimitive?.content ?: "venue rejected"
                bus.publish(BrokerEvent.OrderRejected(id, venueId, reason, strategyId, now))
            }
        }
    }
}
