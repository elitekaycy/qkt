package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Settles an unanswered placement from deal history when the venue shows neither a resting order
 * nor an open position for it. E.g. a market buy that timed out, filled, and was stopped out before
 * anyone looked: its opening deal is replayed as the fill and its closing deals as the exit, so the
 * strategy books the whole round trip instead of a rejection.
 */
internal class MT5UnknownDealReplay(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /** What one look at deal history established about the placement. */
    enum class Outcome {
        /** Deals proved the order executed and its events are published. */
        RESOLVED,

        /** Unreadable or ambiguous history: neither proof of execution nor of absence. */
        INCONCLUSIVE,

        /** A clean read holding no deal that could be this order. */
        CLEAN_ABSENCE,
    }

    fun resolveFromDealHistory(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: MT5PositionProtection?,
        brokerSymbol: String,
        wireComment: String,
        positions: List<MT5Position>,
        attempt: Int,
    ): Outcome {
        val deals =
            client.getDeals(
                fromUtcMs = placementStartedAtMs - MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS,
                toUtcMs =
                    maxOf(clock.now(), placementStartedAtMs) +
                        MT5UnknownOutcomeMatching.CORRELATION_WINDOW_MS,
            ) ?: return Outcome.INCONCLUSIVE
        val dealCandidates =
            deals.filter {
                it.entry == 0 &&
                    it.magic == profile.magic &&
                    it.symbol == brokerSymbol &&
                    !books.positionBook.isAttributed(it.positionTicket) &&
                    (
                        it.clientOrderId == placement.clientOrderId ||
                            matchesOrderComment(it.comment, wireComment)
                    )
            }
        val exactDealGroups =
            dealCandidates
                .filter { it.clientOrderId == placement.clientOrderId }
                .groupBy(MT5UnknownOutcomeMatching::dealPositionKey)
        val fallbackDealGroups =
            if (exactDealGroups.isEmpty()) {
                dealCandidates
                    .groupBy(MT5UnknownOutcomeMatching::dealPositionKey)
                    .filterValues {
                        MT5UnknownOutcomeMatching.matchesUnknownDeals(
                            it,
                            placement,
                            placementStartedAtMs,
                        )
                    }
            } else {
                emptyMap()
            }
        val dealGroups = if (exactDealGroups.isNotEmpty()) exactDealGroups else fallbackDealGroups
        if (dealGroups.size > 1 || (dealGroups.isEmpty() && dealCandidates.isNotEmpty())) {
            log.error(
                "MT5Broker {} order {} deal-history outcome remains ambiguous on attempt {}/{}: {}",
                profile.name,
                request.id,
                attempt,
                MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS,
                dealCandidates.map { it.ticket },
            )
            return Outcome.INCONCLUSIVE
        }
        val openingDeals = dealGroups.values.singleOrNull()
        if (openingDeals != null) {
            if (resolveUnknownDeals(request, protection, openingDeals, deals, positions)) return Outcome.RESOLVED
            return Outcome.INCONCLUSIVE
        }
        return Outcome.CLEAN_ABSENCE
    }

    /** Replay a deal-proven ambiguous placement, including its close legs when already flat. */
    private fun resolveUnknownDeals(
        request: OrderRequest,
        protection: MT5PositionProtection?,
        openingDeals: List<MT5Deal>,
        allDeals: List<MT5Deal>,
        positions: List<MT5Position>,
    ): Boolean {
        val positionTicket = MT5UnknownOutcomeMatching.dealPositionKey(openingDeals.first())
        if (positionTicket <= 0L) return false
        val quantity = openingDeals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        if (quantity.signum() <= 0) return false
        val price = MT5UnknownOutcomeMatching.weightedDealPrice(openingDeals) ?: return false
        val positionOpen = positions.any { it.ticket == positionTicket }
        val positionDeals = allDeals.filter { MT5UnknownOutcomeMatching.dealPositionKey(it) == positionTicket }
        val closingDeals = positionDeals.filter { it.entry != 0 }.sortedBy { it.timeMs }
        if (!positionOpen) {
            val closedQuantity = closingDeals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
            if (closedQuantity.compareTo(quantity) < 0) return false
        }
        if (positionOpen) {
            books.positionBook.track(
                positionTicket,
                MT5TicketMeta(
                    request.id,
                    request.strategyId,
                    protection,
                ),
                request.symbol,
                openingDeals.minOf {
                    it.timeMs
                },
            )
        }
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = positionTicket.toString(),
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = positionTicket.toString(),
                symbol = request.symbol,
                side = request.side,
                price = price,
                quantity = quantity,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        if (!positionOpen) {
            val venueCosts =
                books.venueCostLedger.book(
                    positionTicket,
                    positionDeals,
                    positionClosed = true,
                    nowMs = clock.now(),
                )
            closingDeals.forEachIndexed { index, deal ->
                bus.publish(
                    BrokerEvent.OrderFilled(
                        clientOrderId = request.id,
                        brokerOrderId = positionTicket.toString(),
                        symbol = request.symbol,
                        side = if (deal.type == 0) Side.BUY else Side.SELL,
                        price = deal.price,
                        quantity = deal.volume,
                        strategyId = request.strategyId,
                        timestamp = clock.now(),
                        updatesOrderExecution = false,
                        venueCosts = if (index == closingDeals.lastIndex) venueCosts else BigDecimal.ZERO,
                        exitReason = closingDealExitReason(listOf(deal)),
                    ),
                )
            }
        }
        log.info(
            "MT5Broker {} order {} resolved from deal history as {} ticket {}",
            profile.name,
            request.id,
            if (positionOpen) "FILLED" else "FILLED_AND_CLOSED",
            positionTicket,
        )
        return true
    }
}
