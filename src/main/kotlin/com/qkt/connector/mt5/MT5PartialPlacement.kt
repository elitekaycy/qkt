package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import org.slf4j.LoggerFactory

/**
 * Handles a market entry the venue answered with "done, partially": finds which position the
 * filled part opened, then publishes the first slice and starts tracking the rest. E.g. 1.00 lot
 * sent, reply says 0.40 done under order 900 / deal 7001: deal 7001's `position_id` (say 500) is
 * the position ticket, the strategy gets Accepted + PartiallyFilled(0.40), and order 900 is watched
 * as the residual. [seedTrackedTickets] tells the pending-order poller to watch that residual.
 */
internal class MT5PartialPlacement(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val events: MT5BrokerEvents,
    private val unknownResolver: MT5UnknownResolveScheduler,
    private val partialEntries: MT5PartialEntries,
    private val seedTrackedTickets: (Set<Long>) -> Unit,
    private val unknownResolveBackoffMs: Long,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /**
     * MqlTradeResult separates order and deal tickets and exposes no position ticket. Resolve
     * the exact deal through venue history, whose `position_id` is the authoritative key used by
     * `/get_positions`; never infer that the residual order ticket owns the same numeric id.
     */
    fun resolvePartialPlacement(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: MT5PositionProtection?,
        response: MT5OrderResponse,
    ) {
        for (attempt in 1..MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS) {
            val deals =
                client.getDeals(
                    fromUtcMs = placementStartedAtMs - MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS,
                    toUtcMs =
                        maxOf(
                            clock.now(),
                            placementStartedAtMs,
                        ) + MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS,
                )
            val openingDeal =
                deals
                    ?.singleOrNull {
                        it.ticket == response.result.deal &&
                            it.entry == 0 &&
                            it.positionTicket > 0L &&
                            (it.orderTicket == 0L || it.orderTicket == response.result.order) &&
                            it.magic == profile.magic &&
                            it.symbol == placement.symbol &&
                            it.type == (if (request.side == Side.BUY) 0 else 1)
                    }
            if (openingDeal != null) {
                publishInitialPartialEntry(
                    request,
                    placement,
                    placementStartedAtMs,
                    protection,
                    response,
                    openingDeal,
                )
                return
            }
            if (attempt < MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS) {
                try {
                    Thread.sleep(unknownResolveBackoffMs * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        log.error(
            "MT5Broker {} partial entry {} cannot resolve deal {} to a position ticket; " +
                "retaining UNKNOWN outcome and retrying without assuming order-ticket identity",
            profile.name,
            request.id,
            response.result.deal,
        )
        events.publishGatewayUnreachable(MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS)
        unknownResolver.scheduleUnknownResolution {
            resolvePartialPlacement(request, placement, placementStartedAtMs, protection, response)
        }
    }

    private fun publishInitialPartialEntry(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: MT5PositionProtection?,
        response: MT5OrderResponse,
        openingDeal: MT5Deal,
    ) {
        val residualTicket = response.result.order
        val positionTicket = openingDeal.positionTicket
        val filledQuantity = requireNotNull(response.result.volume)
        // Async-fill venues report price 0.0 on the acknowledgement (#1092); the opening deal
        // carries the executed price.
        val fillPrice = response.result.price.takeIf { it.signum() > 0 } ?: openingDeal.price
        val meta = MT5TicketMeta(request.id, request.strategyId, protection)
        val earlyPosition =
            partialEntries.registerPartialEntry(
                PartialEntryState(
                    meta = meta,
                    residualTicket = residualTicket,
                    positionTicket = positionTicket,
                    symbol = request.symbol,
                    side = request.side,
                    requestedQuantity = placement.volume,
                    cumulativeFilled = filledQuantity,
                    averageFillPrice = fillPrice,
                ),
                openedAtMs = openingDeal.timeMs.takeIf { it > 0L } ?: placementStartedAtMs,
            )
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = residualTicket.toString(),
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = request.id,
                brokerOrderId = positionTicket.toString(),
                symbol = request.symbol,
                side = request.side,
                price = fillPrice,
                quantity = filledQuantity,
                cumulativeFilled = filledQuantity,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        earlyPosition?.let(partialEntries::reconcilePartialEntry)
        if (books.partialPositionByResidualTicket.containsKey(residualTicket)) {
            seedTrackedTickets(setOf(residualTicket))
        }
    }
}
