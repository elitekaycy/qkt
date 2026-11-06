package com.qkt.connectivity

import com.qkt.instrument.EventTerms
import com.qkt.instrument.SlotSymbol
import java.math.BigDecimal

/**
 * A venue that lists event contracts, spoken to directly for `qkt bot` one-shot commands: its catalog filtered as a
 * strategy would read it ([markets]), one event's contracts with their quotes ([chain]), the contract a slot follows
 * now ([follow]), and why a contract takes no order now ([refusal]). A [DirectVenue] that lists event contracts
 * offers this too.
 */
interface DirectEventVenue {
    /** The event contracts the venue lists now that pass [filter], in code order. */
    fun markets(filter: MarketFilter): List<DirectMarket>

    /**
     * Every contract of family [root]'s open event [eventOffset] (0 the event that stops trading first, 1 the next),
     * in strike order, each with its latest quote; null when the family has no such event open now.
     */
    fun chain(
        root: String,
        eventOffset: Int,
    ): DirectChain?

    /** The contract [slot] follows now, as a strategy's slot picks it; fails naming why when none fits. */
    fun follow(slot: SlotSymbol): String

    /** Why event contract [qktSymbol] takes no new order now (the venue paused it, or closes it out), or null. */
    fun refusal(qktSymbol: String): String?
}

/** Which event contracts a catalog read keeps: of [category] and of family [family] when set, and only open ones. */
data class MarketFilter(
    val category: String?,
    val family: String?,
    val openOnly: Boolean,
)

/** One listed event contract: its qkt [symbol], its [terms], its venue [category] and its [trading] mode. */
data class DirectMarket(
    val symbol: String,
    val terms: EventTerms,
    val category: String?,
    val trading: String,
)

/** One open [event] of a family, trading until [closeAtMs], with its [contracts] in strike order. */
data class DirectChain(
    val event: String,
    val closeAtMs: Long,
    val contracts: List<DirectChainContract>,
)

/** One contract of a [DirectChain] with its latest quote; a side the book lacks is null. */
data class DirectChainContract(
    val market: DirectMarket,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
)
