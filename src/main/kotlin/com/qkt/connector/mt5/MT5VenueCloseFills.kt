package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Publishes the fill for a close the position poller observed at the venue, priced from the
 * closing deals it has not booked yet, e.g. ticket 999's take-profit deal 402 at 1.1100 for 0.06.
 * Each deal is used once per ticket; deals the engine already booked as its own close are marked
 * seen through [markSeen] so they never price a venue close.
 */
internal class MT5VenueCloseFills(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val closedTicketMeta: ((Long) -> ClosedPositionMeta?)?,
    private val venueCostsForClose: ((Long, List<MT5Deal>, Boolean) -> BigDecimal)?,
    private val priceProvider: MarketPriceProvider?,
) {
    private val log = LoggerFactory.getLogger(MT5PositionPoller::class.java)
    private val observedClosingDeals: MutableMap<Long, MutableSet<Long>> = mutableMapOf()

    /** Deals of [ticket] another path already booked. */
    fun markSeen(
        ticket: Long,
        dealTickets: Set<Long>,
    ) {
        if (dealTickets.isNotEmpty()) observedClosingDeals.getOrPut(ticket) { mutableSetOf() }.addAll(dealTickets)
    }

    fun forget(ticket: Long) {
        observedClosingDeals.remove(ticket)
    }

    fun publish(
        qktSymbol: String,
        closeSide: Side,
        quantity: java.math.BigDecimal,
        ticket: Long,
        now: Long,
        dealsFromUtcMs: Long,
        fallbackPrice: BigDecimal,
        positionClosed: Boolean,
        /** Owner when the ticket has no qkt-side meta (a leg restored before this session). */
        strategyId: String?,
    ) {
        val meta = closedTicketMeta?.invoke(ticket)
        val clientOrderId =
            meta?.clientOrderId
                ?: "mt5-close-$ticket".also {
                    log.warn(
                        "MT5 poller for {} saw ticket {} close with no qkt-side meta — using synthetic attribution",
                        profile.name,
                        ticket,
                    )
                }
        val deal = client.getClosingDeal(ticket, fromUtcMs = dealsFromUtcMs, toUtcMs = now)
        val seenDeals = observedClosingDeals.getOrPut(ticket) { mutableSetOf() }
        val newDeals = deal?.deals.orEmpty().filter { seenDeals.add(it.ticket) }
        val newClosingDeals = newDeals.filter { it.entry != 0 && it.volume.signum() > 0 && it.price.signum() > 0 }
        val venueCosts =
            venueCostsForClose?.invoke(ticket, deal?.deals.orEmpty(), positionClosed)
                ?: costsForDeals(newDeals)
                ?: BigDecimal.ZERO
        val closePrice =
            closingPrice(newClosingDeals)
                ?: deal?.price
                ?: (priceProvider?.lastPrice(qktSymbol) ?: fallbackPrice).also { fallback ->
                    log.warn(
                        "MT5 poller for {} pricing close of ticket {} from local proxy {} — closing deal unavailable",
                        profile.name,
                        ticket,
                        fallback.toPlainString(),
                    )
                }
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = clientOrderId,
                brokerOrderId = ticket.toString(),
                symbol = qktSymbol,
                side = closeSide,
                price = closePrice,
                quantity = quantity,
                strategyId = meta?.strategyId ?: strategyId ?: "",
                timestamp = now,
                updatesOrderExecution = false,
                venueCosts = venueCosts,
                exitReason = closingDealExitReason(newClosingDeals),
            ),
        )
    }

    private fun closingPrice(deals: List<MT5Deal>): BigDecimal? {
        if (deals.isEmpty()) return null
        val volume = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        if (volume.signum() == 0) return null
        val notional = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.price.multiply(deal.volume) }
        return notional.divide(volume, com.qkt.common.Money.CONTEXT)
    }

    private fun costsForDeals(deals: List<MT5Deal>): BigDecimal? =
        deals
            .takeIf { it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO) { total, deal ->
                total - deal.commission - deal.swap - deal.fee
            }
}
