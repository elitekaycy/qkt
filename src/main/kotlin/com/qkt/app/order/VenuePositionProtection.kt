package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * Stop-loss and take-profit changes sent to an open venue position, and what happens when the
 * venue refuses one. The request is asynchronous; its outcome returns on the bus as
 * [BrokerEvent.PositionModificationCompleted] and is matched here by operation id.
 *
 * A refused attach must never leave a position naked: a stack layer or fill-anchored bracket
 * falls back to an engine-held stop ([armStackFallbackStop] / [armBracketFallbackStop]), and
 * every refusal raises an operator alert.
 */
internal class VenuePositionProtection(
    private val broker: Broker,
    private val bus: EventBus,
    private val ops: OrderOps,
    private val closeTicket: (OrderRequest) -> String?,
    private val armStackFallbackStop: (
        stackId: String,
        layerOrderId: String,
        fillPrice: BigDecimal,
        ticket: String,
    ) -> BigDecimal?,
    private val armBracketFallbackStop: (stop: OrderRequest.Stop, ticket: String) -> Unit,
) {
    private sealed interface Pending

    private data class StackLayer(
        val stackId: String,
        val layerOrderId: String,
        val fillPrice: BigDecimal,
        val stopLoss: BigDecimal?,
        val ticket: String,
        val strategyId: String,
    ) : Pending

    private data class Bracket(
        val ticket: String,
        val strategyId: String,
        val fallbackStop: OrderRequest.Stop?,
    ) : Pending

    private data class Ratchet(
        val orderId: String,
        val ticket: String,
        val strategyId: String,
        val stopLoss: BigDecimal,
    ) : Pending

    private val pending: MutableMap<String, Pending> = mutableMapOf()

    /** Attaches a filled stack layer's exits to its venue position [ticket]. */
    fun attachStackLayer(
        operationId: String,
        stackId: String,
        layerOrderId: String,
        fillPrice: BigDecimal,
        ticket: String,
        strategyId: String,
        stopLoss: BigDecimal?,
        takeProfit: BigDecimal?,
    ) {
        pending[operationId] = StackLayer(stackId, layerOrderId, fillPrice, stopLoss, ticket, strategyId)
        modify(operationId, ticket, stopLoss, takeProfit)
    }

    /** Re-anchors a filled bracket's exits on its venue position [ticket]. */
    fun attachBracket(
        operationId: String,
        ticket: String,
        strategyId: String,
        fallbackStop: OrderRequest.Stop?,
        stopLoss: BigDecimal,
        takeProfit: BigDecimal,
    ) {
        pending[operationId] = Bracket(ticket, strategyId, fallbackStop)
        modify(operationId, ticket, stopLoss, takeProfit)
    }

    /** Moves the venue's copy of an engine-managed stop to [stopLoss] after it tightened. */
    fun ratchet(
        managed: ManagedOrder,
        stopLoss: BigDecimal,
        transition: String,
    ) {
        if (OrderTypeCapability.POSITION_MODIFY !in broker.capabilitiesFor(managed.request.symbol)) return
        val ticket = closeTicket(managed.request) ?: return
        val operationId = "ratchet:${managed.id}:$transition"
        pending[operationId] = Ratchet(managed.id, ticket, managed.request.strategyId, stopLoss)
        modify(operationId, ticket, stopLoss, null)
    }

    /** Handles the venue's answer to an earlier request; a refusal arms the fallback and alerts. */
    fun onCompleted(event: BrokerEvent.PositionModificationCompleted) {
        val request = pending.remove(event.operationId) ?: return
        if (event.accepted) return
        when (request) {
            is StackLayer -> {
                val fallbackStop =
                    armStackFallbackStop(request.stackId, request.layerOrderId, request.fillPrice, request.ticket)
                ops.reportProtectionFailure(
                    request.strategyId,
                    "venue rejected attached SL/TP for ticket ${request.ticket}: ${event.rejectReason}; " +
                        if (fallbackStop != null && request.stopLoss != null) {
                            "engine-held stop armed at ${request.stopLoss.toPlainString()}"
                        } else {
                            "no stop-loss was configured for fallback"
                        },
                )
            }
            is Bracket -> {
                request.fallbackStop?.let { armBracketFallbackStop(it, request.ticket) }
                ops.reportProtectionFailure(
                    request.strategyId,
                    "venue rejected fill-anchored bracket modify for ticket ${request.ticket}: " +
                        "${event.rejectReason}; " +
                        if (request.fallbackStop != null) {
                            "engine-held stop armed at ${request.fallbackStop.stopPrice.toPlainString()}"
                        } else {
                            "engine-managed protection remains active"
                        },
                )
            }
            is Ratchet ->
                ops.reportProtectionFailure(
                    request.strategyId,
                    "venue rejected stop ratchet ${request.orderId} at ${request.stopLoss} " +
                        "for ticket ${request.ticket}: ${event.rejectReason}; engine trigger remains active",
                )
        }
    }

    private fun modify(
        operationId: String,
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ) {
        runCatching {
            broker.modifyPositionAsync(ticket, sl, tp) { ack ->
                bus.publish(
                    BrokerEvent.PositionModificationCompleted(
                        operationId = operationId,
                        ticket = ticket,
                        accepted = ack.accepted,
                        rejectReason = ack.rejectReason,
                    ),
                )
            }
        }.onFailure { error ->
            bus.publish(
                BrokerEvent.PositionModificationCompleted(
                    operationId = operationId,
                    ticket = ticket,
                    accepted = false,
                    rejectReason = error.message,
                ),
            )
        }
    }
}
