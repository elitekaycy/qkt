package com.qkt.broker.exchange

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * One position settled at expiry: [strategyId]'s [quantity] of [contract] closed on [side] at
 * [price], the settlement price per unit. For a future that is the catalog's delivery price when
 * [deliveryPriceKnown], else the last price; for an option it is the intrinsic value at the
 * catalog's delivery price (always known: an option expiring without one fails the run).
 */
data class Settlement(
    val atMs: Long,
    val strategyId: String,
    val contract: String,
    val side: Side,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val deliveryPriceKnown: Boolean,
)

/** Every settlement of a run, in order; the source of `settlements.csv`. */
class SettlementLog {
    private val recorded = mutableListOf<Settlement>()

    /** Record [settlement]. */
    fun record(settlement: Settlement) {
        recorded += settlement
    }

    /** The settlements recorded so far, oldest first. */
    val entries: List<Settlement> get() = recorded.toList()
}
