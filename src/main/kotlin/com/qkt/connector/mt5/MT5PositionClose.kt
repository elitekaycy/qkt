package com.qkt.connector.mt5

import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Closes a venue position by its ticket rather than by sending an opposite order, e.g. a CLOSE
 * rule on ticket 3258722177 asks the gateway to close that ticket and the strategy receives
 * `OrderAccepted` then `OrderFilled` at the closing deal's price with the venue's costs attached.
 * [MT5AcknowledgedCloseFill] prices the fill, including acks that arrive with price 0.0. A close
 * the venue fills only in part (DONE_PARTIAL, 0.04 of 0.10) books that part as a slice and sends
 * the rest again, up to [CLOSE_ATTEMPTS] sends; if the rest cannot be closed the order ends
 * cancelled with the remainder still open at the venue, its stop and target in place (#1353).
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
     * Close the venue position [ticketStr] via the gateway instead of placing an opposite order,
     * which on a hedging account would open a counter. The close is published under [request.id]
     * so the strategy's tracker realizes it. Non-blocking: the send runs on OkHttp's dispatcher,
     * since closes ride the engine thread exactly when exits matter. The engine-close marker is set
     * BEFORE the send (the poller could see the position gone before the reply lands).
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
        send(CloseRun(request, ticket, brokerSymbol, closeQuantity, closeStartedAtMs))
        return SubmitAck(request.id, ticket.toString(), accepted = true)
    }

    /** One close order of [requested] lots on [ticket]; [filled] and [deals] are the slices booked so far. */
    private data class CloseRun(
        val request: OrderRequest.Market,
        val ticket: Long,
        val brokerSymbol: String,
        val requested: BigDecimal,
        val startedAtMs: Long,
        val filled: BigDecimal = BigDecimal.ZERO,
        val deals: Set<Long> = emptySet(),
        val attempt: Int = 1,
    ) {
        val remaining: BigDecimal get() = requested - filled
    }

    private fun send(run: CloseRun) =
        client.closePositionAsync(run.ticket, run.remaining, run.request.partialClose) { onReply(run, it) }

    private fun onReply(
        run: CloseRun,
        resp: MT5OrderResponse,
    ) {
        val request = run.request
        val ticket = run.ticket
        if (!isOrderSuccessful(resp.result.retcode)) {
            val message = resp.errorMessage ?: "close_position retcode=${resp.result.retcode}"
            // Part of the position is already closed and booked: what the venue does with the
            // rest from here on is the position poller's to book.
            if (run.filled.signum() > 0) return endPartlyClosed(run, message)
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
                        run.requested,
                        run.startedAtMs,
                        message,
                        venueReportedClosed,
                    )
                }
                return
            }
            engineCloses.remove(ticket)
            events.reject(request, message)
            return
        }
        val partiallyFilled = resp.result.retcode == MT5_TRADE_RETCODE_DONE_PARTIAL
        val reportedVolume = resp.result.volume?.takeIf { it.signum() > 0 }
        if (partiallyFilled && reportedVolume == null) {
            if (run.filled.signum() > 0) return endPartlyClosed(run, "the venue filled an unreported part")
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
            log.error("MT5Broker {} partial close {} omitted actual filled volume", profile.name, request.id)
            return
        }
        // DONE_PARTIAL (10010) with a volume below what was asked: book that much, close the rest.
        val slice = reportedVolume?.min(run.remaining) ?: run.remaining
        val next =
            run.copy(
                filled = run.filled + slice,
                deals = run.deals + setOfNotNull(resp.result.deal.takeIf { it > 0L }),
                attempt = run.attempt + 1,
            )
        val complete = !partiallyFilled || next.remaining.signum() <= 0
        closeFill.book(
            MT5AcknowledgedCloseFill.AcknowledgedClose(
                request = request,
                ticket = ticket,
                brokerSymbol = run.brokerSymbol,
                ack = resp.result,
                filledQuantity = slice,
                closeStartedAtMs = run.startedAtMs,
                positionClosed = !(request.partialClose || partiallyFilled),
                cumulativeFilled = next.filled,
                orderComplete = complete,
                earlierDeals = run.deals,
                onBooked = { if (!complete) closeRest(next) },
            ),
        )
    }

    private fun closeRest(run: CloseRun) {
        if (run.attempt > CLOSE_ATTEMPTS) {
            return endPartlyClosed(run, "the venue filled only part of the close $CLOSE_ATTEMPTS times")
        }
        log.warn("MT5Broker {} close {} filled {}; sending the rest", profile.name, run.request.id, run.filled)
        send(run)
    }

    /**
     * Ends a close order the venue filled only in part: the booked part stays booked, the rest of
     * the position stays open at the venue with its stop and target, and the operator is told.
     */
    private fun endPartlyClosed(
        run: CloseRun,
        cause: String,
    ) {
        engineCloses.confirmEngineClose(run.ticket, EnginePartialClose(run.filled, run.deals))
        val reason =
            "close filled ${run.filled.toPlainString()} of ${run.requested.toPlainString()}; " +
                "${run.remaining.toPlainString()} left open at the venue on ticket ${run.ticket}: $cause"
        log.error("MT5Broker {} OPERATOR ATTENTION close {}: {}", profile.name, run.request.id, reason)
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = run.request.id,
                brokerOrderId = run.ticket.toString(),
                reason = reason,
                strategyId = run.request.strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    private companion object {
        /** Sends of one close order, the first included, while the venue keeps filling only part of it. */
        const val CLOSE_ATTEMPTS: Int = 3
    }
}
