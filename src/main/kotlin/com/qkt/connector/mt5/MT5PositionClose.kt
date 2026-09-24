package com.qkt.connector.mt5

import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import org.slf4j.LoggerFactory

/**
 * Closes a venue position by its ticket rather than by sending an opposite order, e.g. a CLOSE
 * rule on ticket 3258722177 asks the gateway to close that ticket and the strategy receives
 * `OrderAccepted` then `OrderFilled` at the closing deal's price with the venue's costs attached.
 * [MT5AcknowledgedCloseFill] prices the fill, including acks that arrive with price 0.0.
 */
internal class MT5PositionClose(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val mt5Symbol: MT5Symbol,
    private val placementPrep: MT5PlacementPreparation,
    private val books: MT5BrokerState,
    private val events: MT5BrokerEvents,
    private val engineCloses: MT5EngineCloseMarkers,
    private val unknownResolver: MT5UnknownResolveScheduler,
    private val unknownClose: MT5UnknownCloseResolution,
    private val closeTruth: MT5CloseVenueTruth,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)
    private val closeFill =
        MT5AcknowledgedCloseFill(profile, bus, clock, books, engineCloses, unknownResolver, closeTruth)

    /**
     * Close the venue position [ticketStr] via the gateway instead of placing an opposite
     * order. On a hedging account an opposite order opens a counter; this actually closes the
     * position. Emits the close as an attributed [BrokerEvent.OrderFilled] under [request.id]
     * so the strategy's position tracker realizes it, and marks the ticket via
     * [recentlyClosedByTicket] so the position poller does not publish a duplicate close.
     *
     * Non-blocking like [submitSingle]'s market path: the HTTP send runs on OkHttp's dispatcher
     * and the outcome returns as bus events — closes ride CLOSE rules, trailing-stop fires, and
     * flattens on the engine thread, where a blocking round-trip stalls tick processing exactly
     * when exits matter. The poller-suppression mark is set BEFORE the send (the poller could
     * observe the position gone before our callback runs) and rolled back on failure.
     */
    fun submitCloseByTicket(
        request: OrderRequest.Market,
        ticketStr: String,
    ): SubmitAck {
        val ticket =
            ticketStr.toLongOrNull()
                ?: return events.reject(request, "closesTicket is not a valid ticket: $ticketStr")
        val brokerSymbol = mt5Symbol.toBroker(request.symbol.substringAfter(':'))
        val closeQuantity =
            when (val result = placementPrep.prepareVolume(brokerSymbol, request.quantity)) {
                is MT5PlacementPreparation.VolumeResult.Ok -> result.quantity
                is MT5PlacementPreparation.VolumeResult.Reject -> return events.reject(request, result.reason)
            }
        val closeStartedAtMs = clock.now()
        engineCloses.begin(ticket, closeStartedAtMs)
        client.closePositionAsync(ticket, volume = closeQuantity, partial = request.partialClose) { resp ->
            if (!isOrderSuccessful(resp.result.retcode)) {
                val message = resp.errorMessage ?: "close_position retcode=${resp.result.retcode}"
                val venueReportedClosed = MT5SendOutcomes.venueOwnsClose(resp, message)
                if (MT5SendOutcomes.isAmbiguousSendFailure(message) || venueReportedClosed) {
                    // A venue-side exit (mirrored stop, take-profit, manual close) can land
                    // between the engine deciding to close and the close reaching the venue.
                    // The venue then answers POSITION_CLOSED, or FROZEN when the market is
                    // already inside the stop's freeze level: the trade is finishing at the
                    // venue, so the outcome is read from deal history rather than surfaced
                    // as a rejection that would count toward the runaway breaker.
                    unknownResolver.executeUnknownResolution {
                        unknownClose.resolveUnknownCloseOutcome(
                            request,
                            ticket,
                            closeQuantity,
                            closeStartedAtMs,
                            message,
                            venueReportedClosed,
                        )
                    }
                    return@closePositionAsync
                }
                engineCloses.remove(ticket)
                events.reject(request, message)
                return@closePositionAsync
            }
            val partiallyFilled = resp.result.retcode == MT5_TRADE_RETCODE_DONE_PARTIAL
            val reportedVolume = resp.result.volume?.takeIf { it.signum() > 0 }
            if (partiallyFilled && reportedVolume == null) {
                // The venue changed state, so a rejection would invite a duplicate close.
                // Keep the order accepted-but-unresolved and let the position poller
                // reconcile the remaining venue quantity without sending a second close.
                // Remove the pending marker before it can be confirmed: a poll that sees a
                // confirmed marker adopts the reduced snapshot without publishing its delta.
                engineCloses.remove(ticket)
                bus.publish(
                    BrokerEvent.OrderAccepted(
                        clientOrderId = request.id,
                        brokerOrderId = ticket.toString(),
                        strategyId = request.strategyId,
                        timestamp = clock.now(),
                    ),
                )
                log.error(
                    "MT5Broker {} partial close {} omitted actual filled volume",
                    profile.name,
                    request.id,
                )
                return@closePositionAsync
            }
            val positionRemainsOpen = request.partialClose || partiallyFilled
            closeFill.book(
                MT5AcknowledgedCloseFill.AcknowledgedClose(
                    request = request,
                    ticket = ticket,
                    brokerSymbol = brokerSymbol,
                    ack = resp.result,
                    filledQuantity = reportedVolume ?: closeQuantity,
                    closeStartedAtMs = closeStartedAtMs,
                    positionClosed = !positionRemainsOpen,
                ),
            )
        }
        return SubmitAck(request.id, ticket.toString(), accepted = true)
    }
}
