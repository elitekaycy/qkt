package com.qkt.connector.mt5

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal

/**
 * Reads the venue's deal history for a closing position: what the close really cost and the price
 * it really traded at. E.g. a close acknowledged with price 0.0 and deal 0 (async-fill venues) is
 * booked at its closing deal's 2401.35, with that position's commission and swap as the venue costs.
 * [retryBackoffMs] spaces the repeated lookups while such a closing deal is still materializing;
 * [priceTracker] is the engine's quote cache behind [marketClosePrice].
 */
internal class MT5CloseVenueTruth(
    private val client: MT5Client,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val priceTracker: MarketPriceProvider?,
    private val retryBackoffMs: Long,
) {
    /** What the venue's deal history says about a close: newly booked costs and the closing deals' price. */
    data class CloseVenueTruth(
        val costs: BigDecimal,
        val closingDealPrice: BigDecimal?,
    )

    /**
     * One deal-history read for the close of [positionTicket] that [ack] acknowledged. The closing
     * deal is the one [ack] names (deal ticket, else order ticket); an ack naming neither (deal 0,
     * order 0) takes the position's closing deals stamped since [closeStartedAtMs]. Costs are
     * booked idempotently, so a repeated read returns only costs not booked before.
     */
    fun venueTruthForPositionClose(
        positionTicket: Long,
        ack: MT5OrderResult,
        closeStartedAtMs: Long,
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
                        it.positionTicket == positionTicket || (ack.deal != 0L && it.ticket == ack.deal)
                    }
        return CloseVenueTruth(
            costs = books.venueCostLedger.book(positionTicket, deals, positionClosed, now),
            closingDealPrice = MT5UnknownOutcomeMatching.weightedDealPrice(closingDeals(deals, ack, closeStartedAtMs)),
        )
    }

    private fun closingDeals(
        deals: List<MT5Deal>,
        ack: MT5OrderResult,
        closeStartedAtMs: Long,
    ): List<MT5Deal> {
        val closing = deals.filter { it.entry != 0 && it.volume.signum() > 0 && it.price.signum() > 0 }
        // A named deal ticket is exact: until that deal is in history, nothing else stands in for it.
        if (ack.deal != 0L) return closing.filter { it.ticket == ack.deal }
        val byOrder = if (ack.order != 0L) closing.filter { it.orderTicket == ack.order } else emptyList()
        return byOrder.ifEmpty { closing.filter { it.timeMs >= closeStartedAtMs - CLOSE_DEAL_CLOCK_SKEW_MS } }
    }

    /** Delay before closing-deal lookup [attempt] (2, 3, ...): linear backoff on [retryBackoffMs]. */
    fun retryDelayMs(attempt: Int): Long = retryBackoffMs * (attempt - 1)

    /**
     * The best current price for closing through [closeSide] on [qktSymbol] ([brokerSymbol] at the
     * venue): the engine's side-aware quote (bid to sell, ask to buy), then its last trade price,
     * then the gateway's live tick. Null when none is positive.
     */
    fun marketClosePrice(
        qktSymbol: String,
        brokerSymbol: String,
        closeSide: Side,
    ): BigDecimal? {
        priceTracker?.executionPrice(qktSymbol, closeSide)?.takeIf { it.signum() > 0 }?.let { return it }
        priceTracker?.lastPrice(qktSymbol)?.takeIf { it.signum() > 0 }?.let { return it }
        val tick = client.getTick(brokerSymbol) ?: return null
        return (if (closeSide == Side.SELL) tick.bid else tick.ask).takeIf { it.signum() > 0 }
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

        /**
         * How far before the close was sent an unidentified closing deal may be stamped and still
         * belong to it. Venue and host clocks drift by seconds; earlier partial closes of the same
         * ticket are normally minutes apart, and would trade at nearly the same price if not.
         */
        const val CLOSE_DEAL_CLOCK_SKEW_MS: Long = 5_000L
    }
}
