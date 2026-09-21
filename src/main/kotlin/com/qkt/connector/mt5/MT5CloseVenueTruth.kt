package com.qkt.connector.mt5

import com.qkt.common.Clock
import java.math.BigDecimal

/**
 * Reads the venue's deal history for a closing position: what the close really cost and the price
 * it really traded at. E.g. a close acknowledged with price 0.0 (async-fill venues) is booked at
 * its closing deal's 2401.35, with that position's commission and swap as the venue costs.
 */
internal class MT5CloseVenueTruth(
    private val client: MT5Client,
    private val clock: Clock,
    private val books: MT5BrokerState,
) {
    /** What the venue's deal history says about a close: booked costs and the closing deal's price. */
    data class CloseVenueTruth(
        val costs: BigDecimal,
        val closingDealPrice: BigDecimal?,
    )

    fun venueTruthForPositionClose(
        positionTicket: Long,
        closingDealTicket: Long,
        positionClosed: Boolean,
    ): CloseVenueTruth {
        val now = clock.now()
        val from = books.positionBook.openedAt(positionTicket) ?: now - DEAL_LOOKUP_WINDOW_MS
        val deals =
            client.getPositionDeals(positionTicket, fromUtcMs = from, toUtcMs = now)
                ?: client
                    .getDeals(
                        fromUtcMs = now - DEAL_LOOKUP_WINDOW_MS,
                        toUtcMs = now + DEAL_LOOKUP_WINDOW_MS,
                    ).orEmpty()
                    .filter {
                        it.positionTicket == positionTicket || it.ticket == closingDealTicket
                    }
        return CloseVenueTruth(
            costs = books.venueCostLedger.book(positionTicket, deals, positionClosed, now),
            closingDealPrice =
                deals
                    .firstOrNull { it.ticket == closingDealTicket && it.price.signum() > 0 }
                    ?.price,
        )
    }

    fun bookVenueCloseCosts(
        positionTicket: Long,
        deals: List<MT5Deal>,
        positionClosed: Boolean,
    ): BigDecimal {
        if (positionClosed) books.positionBook.forgetOpenedAt(positionTicket)
        return books.venueCostLedger.book(positionTicket, deals, positionClosed = positionClosed, nowMs = clock.now())
    }

    private companion object {
        /** Deal-history window around an immediate fill used to retrieve its exact venue costs. */
        const val DEAL_LOOKUP_WINDOW_MS: Long = 24L * 60L * 60L * 1_000L
    }
}
