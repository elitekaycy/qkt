package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Settles a close whose reply did not say whether it happened, by asking the venue. E.g. closing
 * ticket 3258722177 times out: closing deals on that ticket mean it closed (publish the fill at
 * their volume-weighted price), four clean reads with the position still open mean it did not
 * (reject), and unreadable venue answers mean try again later without emitting anything.
 * [hasPublishedClose] is the position poller's record of closes it already put on the bus.
 */
internal class MT5UnknownCloseResolution(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val events: MT5BrokerEvents,
    private val engineCloses: MT5EngineCloseMarkers,
    private val unknownResolver: MT5UnknownResolveScheduler,
    private val hasPublishedClose: (Long) -> Boolean,
    private val unknownResolveBackoffMs: Long,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /**
     * [venueReportedClosed] marks a close the venue answered with `POSITION_CLOSED`: the
     * closing deal then predates this close attempt (a venue-side stop or take-profit
     * fired first), so deal correlation looks back over the full correlation window
     * instead of only the clock-skew margin used for a close of unknown delivery.
     */
    fun resolveUnknownCloseOutcome(
        request: OrderRequest.Market,
        ticket: Long,
        requestedQuantity: BigDecimal,
        closeStartedAtMs: Long,
        cause: String,
        venueReportedClosed: Boolean = false,
    ) {
        log.warn(
            "MT5Broker {} close {} outcome UNKNOWN ({}) — querying venue before resolving",
            profile.name,
            request.id,
            cause,
        )
        val dealsNotBeforeMs =
            closeStartedAtMs -
                if (venueReportedClosed) MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS else CLOSE_DEAL_CLOCK_SKEW_MS
        var cleanAbsenceReads = 0
        for (attempt in 1..MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS) {
            Thread.sleep(unknownResolveBackoffMs * attempt)
            val positions = client.getPositions(magic = profile.magic) ?: continue
            val deals =
                client.getPositionDeals(
                    positionTicket = ticket,
                    fromUtcMs = closeStartedAtMs - MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS,
                    toUtcMs = maxOf(clock.now(), closeStartedAtMs) + MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS,
                ) ?: continue
            val position = positions.firstOrNull { it.ticket == ticket }
            val closingDeals =
                deals
                    .filter {
                        it.positionTicket == ticket &&
                            it.magic == profile.magic &&
                            it.entry != 0 &&
                            it.timeMs >= dealsNotBeforeMs
                    }.sortedBy { it.timeMs }
            if (closingDeals.isNotEmpty()) {
                val filledQuantity = closingDeals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
                val fillPrice = MT5UnknownOutcomeMatching.weightedDealPrice(closingDeals)
                if (filledQuantity.signum() > 0 && fillPrice != null && fillPrice.signum() > 0) {
                    val positionRemainsOpen = position != null
                    engineCloses.confirmEngineClose(ticket)
                    if (!positionRemainsOpen) {
                        books.positionBook.forget(ticket)
                    }
                    val venueCosts =
                        books.venueCostLedger.book(
                            ticket,
                            deals,
                            positionClosed = !positionRemainsOpen,
                            nowMs = clock.now(),
                        )
                    if (hasPublishedClose(ticket)) {
                        log.info(
                            "MT5Broker {} close {} was already published by the position poller for ticket {}",
                            profile.name,
                            request.id,
                            ticket,
                        )
                        // The position's close (and its P&L) is already on the bus under the
                        // entry. Retire this close order without a second fill so the engine
                        // does not keep a live exit child on a position that no longer exists.
                        bus.publish(
                            BrokerEvent.OrderCancelled(
                                clientOrderId = request.id,
                                brokerOrderId = ticket.toString(),
                                reason =
                                    "superseded by venue close of ticket $ticket already published by the position poller",
                                strategyId = request.strategyId,
                                timestamp = clock.now(),
                            ),
                        )
                        return
                    }
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = request.id,
                            brokerOrderId = ticket.toString(),
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    bus.publish(
                        BrokerEvent.OrderFilled(
                            clientOrderId = request.id,
                            brokerOrderId = ticket.toString(),
                            symbol = request.symbol,
                            side = request.side,
                            price = fillPrice,
                            quantity = filledQuantity.min(requestedQuantity),
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                            venueCosts = venueCosts,
                            exitReason = ExitReason.CLOSE,
                        ),
                    )
                    log.info(
                        "MT5Broker {} close {} resolved as FILLED ticket {}",
                        profile.name,
                        request.id,
                        ticket,
                    )
                    return
                }
            }
            if (position != null) cleanAbsenceReads++
        }
        if (cleanAbsenceReads == MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS) {
            engineCloses.remove(ticket)
            events.reject(request, "unknown-state close resolved as not executed after verified retry window ($cause)")
            return
        }
        log.error(
            "MT5Broker {} close {} outcome UNRESOLVED after {} venue queries — no rejection emitted",
            profile.name,
            request.id,
            MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS,
        )
        events.publishGatewayUnreachable(MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS)
        scheduleUnknownCloseResolution(request, ticket, requestedQuantity, closeStartedAtMs, cause, venueReportedClosed)
    }

    private fun scheduleUnknownCloseResolution(
        request: OrderRequest.Market,
        ticket: Long,
        requestedQuantity: BigDecimal,
        closeStartedAtMs: Long,
        cause: String,
        venueReportedClosed: Boolean,
    ) {
        unknownResolver.scheduleUnknownResolution {
            resolveUnknownCloseOutcome(request, ticket, requestedQuantity, closeStartedAtMs, cause, venueReportedClosed)
        }
    }

    private companion object {
        /** How far before the close attempt a closing deal may be stamped and still belong to it. */
        const val CLOSE_DEAL_CLOCK_SKEW_MS: Long = 1_000L
    }
}
