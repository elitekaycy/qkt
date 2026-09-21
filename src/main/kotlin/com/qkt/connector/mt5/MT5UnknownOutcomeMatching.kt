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
        sameOrderType(candidate.type, placement.type) &&
            candidate.volume.compareTo(placement.volume) == 0 &&
            (placement.price == null || candidate.priceOpen.compareTo(placement.price) == 0) &&
            isNearPlacement(candidate.timeSetup, placementStartedAtMs)

    /**
     * The gateway reports a resting order's type as MT5's number (`2`), a placement names it
     * (`BUY_LIMIT`). Comparing the two as text never matched, so a resting order whose placement
     * response was lost could not be recognised as ours: `correlatedMatches=0` with the right ticket
     * in plain sight (#1234).
     */
    fun sameOrderType(
        venueType: String,
        placementType: String,
    ): Boolean {
        val venue = venueType.trim()
        val name = venue.toIntOrNull()?.let { MT5_ORDER_TYPE_NAMES.getOrNull(it) } ?: venue
        return name.equals(placementType.trim(), ignoreCase = true)
    }

    private val MT5_ORDER_TYPE_NAMES =
        listOf("BUY", "SELL", "BUY_LIMIT", "SELL_LIMIT", "BUY_STOP", "SELL_STOP", "BUY_STOP_LIMIT", "SELL_STOP_LIMIT")

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
