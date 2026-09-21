package com.qkt.connector.mt5

import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import org.slf4j.LoggerFactory

/**
 * Places an order that needs several venue tickets (a standalone OCO) as all-or-nothing.
 * E.g. a BUY_STOP above and a SELL_STOP below the range: if the second leg is refused, the first
 * is cancelled at the venue and the whole order is rejected, so a one-legged OCO never runs as a
 * directional bet. Each placed leg is registered under its own engine order id.
 */
internal class MT5CompositePlacement(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val placementPrep: MT5PlacementPreparation,
    private val placementIds: IdGenerator,
    private val books: MT5BrokerState,
    private val events: MT5BrokerEvents,
    private val pendingFills: MT5PendingFills,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    fun submitComposite(
        request: OrderRequest,
        composite: MT5Translation.Composite,
    ): SubmitAck {
        // Prepare every leg first. Prepare is pre-placement validation only; rejecting
        // here doesn't need rollback because no client.placeOrder has run yet.
        val prepared =
            composite.requests.map { wire ->
                when (val result = placementPrep.prepareForPlacement(wire)) {
                    is MT5PlacementPreparation.PrepareResult.Ok -> result.wire
                    is MT5PlacementPreparation.PrepareResult.Reject ->
                        return events.reject(request, "OCO leg ${wire.comment}: ${result.reason}")
                }
            }
        // Place legs sequentially. Each leg's ticket is registered in [pendingBook]
        // so [MT5PositionPoller] can correlate the eventual fill back to a strategy. If
        // any leg rejects, every previously-placed leg is cancelled on the venue and
        // the entire composite is rejected — never leave a one-legged OCO running as a
        // directional bet the caller never intended.
        val placed = mutableListOf<PlacedLeg>()
        for (preparedWire in prepared) {
            val wire = preparedWire.withPlacementId()
            val resp = client.placeOrder(wire)
            if (!isOrderSuccessful(resp.result.retcode)) {
                val reason =
                    "OCO leg ${wire.comment}: ${resp.errorMessage ?: "retcode=${resp.result.retcode}"}"
                log.warn("MT5Broker ${profile.name} $reason; rolling back ${placed.size} placed leg(s)")
                for (leg in placed) {
                    runCatching { client.cancelOrder(leg.ticket) }
                        .onFailure { e ->
                            log.warn(
                                "MT5Broker ${profile.name} OCO rollback cancel(${leg.ticket}) failed: ${e.message}",
                            )
                        }
                    books.pendingBook.forgetLeg(leg.legOrderId, leg.ticket)
                }
                return events.reject(request, reason)
            }
            val ticket = resp.result.order
            if (ticket != 0L) {
                val legOrderId = decodeOcoLegOrderId(wire.comment) ?: request.id
                pendingFills.registerPendingTicket(ticket, MT5TicketMeta(legOrderId, request.strategyId))
                placed.add(PlacedLeg(ticket, legOrderId))
            }
        }

        val firstBrokerId = placed.firstOrNull { it.ticket != 0L }?.ticket?.toString() ?: composite.groupId

        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = firstBrokerId,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = firstBrokerId,
            accepted = true,
        )
    }

    private fun MT5OrderRequest.withPlacementId(): MT5OrderRequest = copy(clientOrderId = placementIds.next())

    private data class PlacedLeg(
        val ticket: Long,
        val legOrderId: String,
    )

    /**
     * Recover the per-leg client order id from an OCO wire comment.
     *
     * [MT5OrderTranslator.translateStandaloneOCO] prepends `"oco:<parent-id>/"` to each
     * leg's original comment (which is the leg's `OrderRequest.id`). The qkt-side comment
     * is full-length even though MT5 truncates to 16 chars on the venue side — we decode
     * before sending, so truncation is not an issue here.
     */
    fun decodeOcoLegOrderId(comment: String): String? {
        if (!comment.startsWith("oco:")) return null
        val slash = comment.indexOf('/')
        if (slash < 0 || slash == comment.length - 1) return null
        return comment.substring(slash + 1)
    }
}
