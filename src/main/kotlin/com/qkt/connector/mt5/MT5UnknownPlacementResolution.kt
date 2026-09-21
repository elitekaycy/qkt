package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import org.slf4j.LoggerFactory

/**
 * Settles a placement whose reply did not say whether the order exists, by asking the venue before
 * telling the strategy anything. E.g. a market buy times out: if a position carries its placement
 * id the strategy gets Accepted + Filled; if four clean reads show nothing it gets Rejected; if the
 * venue cannot be read nothing is emitted and the lookup is rescheduled.
 */
internal class MT5UnknownPlacementResolution(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val mt5Symbol: MT5Symbol,
    private val books: MT5BrokerState,
    private val events: MT5BrokerEvents,
    private val unknownResolver: MT5UnknownResolveScheduler,
    private val pendingFills: MT5PendingFills,
    private val venueMatcher: MT5UnknownVenueMatcher,
    private val dealReplay: MT5UnknownDealReplay,
    private val unknownResolveBackoffMs: Long,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /**
     * Resolve an UNKNOWN send outcome by querying the venue for an order carrying this
     * request's full gateway placement id, with a constrained fallback to the venue-truncated
     * comment for older gateways.
     *
     *   - Found as a pending → the venue owns it: register tickets, publish Accepted.
     *   - Found as a position → it filled: register meta, publish Accepted + Filled.
     *   - Repeated clean order/position/deal reads with no match → publish Rejected.
     *   - Found only in deal history → replay its fill and any completed close legs.
     *   - Reads keep failing → leave the order UNRESOLVED (no event): a false "rejected"
     *     invites a duplicate submission, which is the worse failure. Alert the operator.
     */
    fun resolveUnknownOutcome(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: MT5PositionProtection?,
        cause: String,
    ) {
        val wireComment = placement.comment.take(MT5_COMMENT_MAX_LENGTH)
        val brokerSymbol = mt5Symbol.toBroker(request.symbol.substringAfter(':'))
        log.warn(
            "MT5Broker {} order {} outcome UNKNOWN ({}) — querying venue before resolving",
            profile.name,
            request.id,
            cause,
        )
        var cleanAbsenceReads = 0
        for (attempt in 1..MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS) {
            Thread.sleep(unknownResolveBackoffMs * attempt)
            val pendings = client.getPendingOrders(magic = profile.magic) ?: continue
            val positions = client.getPositions(magic = profile.magic) ?: continue
            val venue =
                venueMatcher.match(pendings, positions, placement, placementStartedAtMs, brokerSymbol, wireComment)
            val pendingCandidates = venue.pendingCandidates
            val positionCandidates = venue.positionCandidates
            val matches = venue.matches
            if (matches.size > 1 || (matches.isEmpty() && (pendingCandidates + positionCandidates).isNotEmpty())) {
                log.error(
                    "MT5Broker {} order {} UNKNOWN outcome remains ambiguous on attempt {}/{}: " +
                        "pendingCandidates={} positionCandidates={} correlatedMatches={}",
                    profile.name,
                    request.id,
                    attempt,
                    MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS,
                    pendingCandidates.map { it.ticket },
                    positionCandidates.map { it.ticket },
                    matches.size,
                )
                continue
            }
            when (val match = matches.singleOrNull()) {
                is UnknownVenueMatch.Pending -> {
                    val pendingMatch = match.order
                    pendingFills.registerPendingTicket(
                        pendingMatch.ticket,
                        MT5TicketMeta(request.id, request.strategyId, protection),
                    )
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = request.id,
                            brokerOrderId = pendingMatch.ticket.toString(),
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    log.info(
                        "MT5Broker {} order {} resolved as PENDING ticket {}",
                        profile.name,
                        request.id,
                        pendingMatch.ticket,
                    )
                    return
                }
                is UnknownVenueMatch.Position -> {
                    val positionMatch = match.position
                    books.positionBook.attribute(
                        positionMatch.ticket,
                        MT5TicketMeta(request.id, request.strategyId, protection),
                    )
                    books.positionBook.setSymbol(positionMatch.ticket, request.symbol)
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = request.id,
                            brokerOrderId = positionMatch.ticket.toString(),
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    bus.publish(
                        BrokerEvent.OrderFilled(
                            clientOrderId = request.id,
                            brokerOrderId = positionMatch.ticket.toString(),
                            symbol = request.symbol,
                            side = request.side,
                            price = positionMatch.priceOpen,
                            quantity = positionMatch.volume,
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    log.info(
                        "MT5Broker {} order {} resolved as FILLED ticket {}",
                        profile.name,
                        request.id,
                        positionMatch.ticket,
                    )
                    return
                }
                null ->
                    when (
                        dealReplay.resolveFromDealHistory(
                            request,
                            placement,
                            placementStartedAtMs,
                            protection,
                            brokerSymbol,
                            wireComment,
                            positions,
                            attempt,
                        )
                    ) {
                        MT5UnknownDealReplay.Outcome.RESOLVED -> return
                        MT5UnknownDealReplay.Outcome.INCONCLUSIVE -> continue
                        MT5UnknownDealReplay.Outcome.CLEAN_ABSENCE -> cleanAbsenceReads++
                    }
            }
        }
        if (cleanAbsenceReads == MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS) {
            events.reject(request, "unknown-state send resolved as not placed after verified retry window ($cause)")
            return
        }
        log.error(
            "MT5Broker {} order {} send outcome UNRESOLVED after {} venue queries — " +
                "no event emitted (a false reject invites a duplicate). Check the venue manually.",
            profile.name,
            request.id,
            MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS,
        )
        events.publishGatewayUnreachable(MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS)
        scheduleUnknownPlacementResolution(request, placement, placementStartedAtMs, protection, cause)
    }

    private fun scheduleUnknownPlacementResolution(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: MT5PositionProtection?,
        cause: String,
    ) {
        unknownResolver.scheduleUnknownResolution {
            resolveUnknownOutcome(request, placement, placementStartedAtMs, protection, cause)
        }
    }
}
