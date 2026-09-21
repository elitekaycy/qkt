package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlin.math.abs

/**
 * Decides whether something the venue holds is the outcome of a placement whose acknowledgement
 * was lost. Pure: every answer comes from the arguments, so the rules can be read and tested
 * without a broker.
 *
 * A candidate matches when it is the same side and size as the placement and the venue stamped it
 * within [CORRELATION_WINDOW_MS] of the moment the placement was sent.
 */
internal object MT5UnknownOutcomeMatching {
    /** How far a venue timestamp may sit from the placement's own send time and still be its outcome. */
    const val CORRELATION_WINDOW_MS: Long = 60_000L

    fun dealPositionKey(deal: MT5Deal): Long =
        deal.positionTicket.takeIf { it > 0L }
            ?: deal.orderTicket.takeIf { it > 0L }
            ?: deal.ticket

    fun matchesUnknownDeals(
        deals: List<MT5Deal>,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
    ): Boolean {
        val expectedType = if (placement.type.startsWith("BUY")) 0 else 1
        val totalVolume = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        return deals.all { it.type == expectedType } &&
            totalVolume.compareTo(placement.volume) == 0 &&
            deals.any { isNearPlacement(it.timeMs, placementStartedAtMs) }
    }

    fun weightedDealPrice(deals: List<MT5Deal>): BigDecimal? {
        val quantity = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        if (quantity.signum() <= 0) return null
        val notional = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.price.multiply(deal.volume) }
        return notional.divide(quantity, com.qkt.common.Money.CONTEXT)
    }

    fun matchesUnknownPending(
        candidate: MT5PendingOrder,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
    ): Boolean =
        candidate.type.equals(placement.type, ignoreCase = true) &&
            candidate.volume.compareTo(placement.volume) == 0 &&
            (placement.price == null || candidate.priceOpen.compareTo(placement.price) == 0) &&
            isNearPlacement(candidate.timeSetup, placementStartedAtMs)

    fun matchesUnknownPosition(
        candidate: MT5Position,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
    ): Boolean {
        val expectedType = if (placement.type.startsWith("BUY")) 0 else 1
        return candidate.type == expectedType &&
            candidate.volume.compareTo(placement.volume) == 0 &&
            isNearPlacement(candidate.openTime, placementStartedAtMs)
    }

    fun isNearPlacement(
        venueEpoch: Long,
        placementStartedAtMs: Long,
    ): Boolean {
        if (venueEpoch <= 0L) return false
        val venueEpochMs = if (venueEpoch < 100_000_000_000L) venueEpoch * 1_000L else venueEpoch
        return abs(venueEpochMs - placementStartedAtMs) <= CORRELATION_WINDOW_MS
    }
}
