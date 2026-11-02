package com.qkt.broker

import java.math.BigDecimal

/**
 * What qkt's accounting booked for venue fill [fillId] of engine order [orderId] at [atMs]: the [leg] it made, the
 * gross P&L it realized and the strategy it belongs to. A venue that states none of it on its deals needs this
 * to report the fill as a deal.
 */
data class BookedFill(
    val orderId: String,
    val fillId: String,
    val leg: VenueLeg,
    val grossRealized: BigDecimal,
    val strategyId: String,
    val atMs: Long,
)

/** The facts a venue's deals are stated from, as a restart must take them back ([BookedDealsVenue]). */
data class BookedDeals(
    val fills: List<BookedFill>,
    val settledCloses: List<SettledClose>,
) {
    /** Whether there is nothing to keep. */
    fun isEmpty(): Boolean = fills.isEmpty() && settledCloses.isEmpty()
}

/**
 * Opt-in capability of a venue whose deals are stated from what qkt booked (a VGP gateway): the facts live in the
 * process, so without this a restart before the next deal read loses the deal (#1570). A venue whose deals
 * carry their own realized P&L (MT5) has no need of it.
 */
interface BookedDealsVenue {
    /** The facts held now for the deals still to be read, oldest first. */
    fun bookedDeals(): BookedDeals

    /** Takes back [saved], the facts held before a restart. */
    fun restoreBookedDeals(saved: BookedDeals)
}

/** Every venue behind this broker that needs its booked deals kept: a router's leaves, or the broker itself. */
internal fun Broker.bookedDealsVenues(): List<BookedDealsVenue> =
    when (this) {
        is CompositeBroker -> allLeaves().distinct().flatMap { it.bookedDealsVenues() }
        is BookedDealsVenue -> listOf(this)
        else -> emptyList()
    }
