package com.qkt.connector.bybit

import com.qkt.connector.bybit.spot.BybitSpotStateRecovery.ManagedOrderView
import com.qkt.execution.ManagedOrder
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Takes back the orders a restart restored for one [category] broker, by id rather than by a time
 * window, as the gateway path does: each order is owned again, so an execution Bybit reports for it later
 * is attributed; its executions are replayed through [replayOrder], skipping, oldest first, those that
 * add up to what the order had already booked (its persisted fill progress); then its current record is
 * applied through [updates], so an order that ended while qkt was down ends in the engine too. An order
 * Bybit no longer lists (open, or in the last 7 days of history) is left for the engine to retire.
 */
class BybitRestartRecovery(
    private val category: String,
    private val transport: BybitTransport,
    private val ends: BybitHeldEnds,
    private val updates: BybitOrderUpdates,
    private val replayOrder: (clientOrderId: String, alreadyBooked: BigDecimal) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Recovers [restored]; returns the ids Bybit knows. Throws when Bybit cannot be read. */
    fun recover(restored: List<ManagedOrder>): Set<String> {
        val known = LinkedHashSet<String>()
        for (order in restored) {
            val record = find(order.id) ?: continue
            val request = order.request
            ends.orders.register(ManagedOrderView(order.id, request.symbol, request.side, request.strategyId))
            ends.orders.booked(order.id, order.cumulativeFilledQuantity)
            replayOrder(order.id, order.cumulativeFilledQuantity)
            updates.apply(record)
            known += order.id
        }
        return known
    }

    private fun find(clientOrderId: String): JsonObject? =
        listOf("/v5/order/realtime", "/v5/order/history").firstNotNullOfOrNull { path ->
            val query = mapOf("category" to category, "orderLinkId" to clientOrderId)
            requireBybitOk(transport.getSigned(path, query), "restored order lookup", json)["result"]
                ?.jsonObject
                ?.get("list")
                ?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["orderLinkId"]?.jsonPrimitive?.content == clientOrderId }
        }
}
