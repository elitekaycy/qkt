package com.qkt.derivatives.options

import java.math.BigDecimal

/** An option [leg] held to its expiry at [expiryMs], valued at [price] per unit of the underlying. */
data class ExpiringLeg(
    val leg: OptionLeg,
    val expiryMs: Long,
    val price: BigDecimal,
)

/** The worst loss of a set of option legs held to their expiries. */
object StructureRisk {
    /**
     * The worst loss of [legs] valued at their prices: each expiry's value (Σ quantity × contract
     * size × price) less its minimum expiry payoff ([OptionPayoff.minimum]), summed over expiries.
     * Expiries are never offset against each other, as in the option margin: a calendar whose long leg
     * lapses first leaves its short alone. Null when any expiry's loss is unbounded.
     *
     * ```kotlin
     * // Short 81000 put at 646, long 78000 put at 219, one expiry: width 3000 less credit 427.
     * StructureRisk.maxLoss(spread) // 2573
     * ```
     */
    fun maxLoss(legs: List<ExpiringLeg>): BigDecimal? {
        var total = BigDecimal.ZERO
        for (group in legs.groupBy { it.expiryMs }.values) {
            val value =
                group.fold(BigDecimal.ZERO) { v, held ->
                    v.add(
                        held.leg.quantity
                            .multiply(held.leg.contractSize)
                            .multiply(held.price),
                    )
                }
            val least = OptionPayoff.minimum(group.map { it.leg }) ?: return null
            total = total.add(value.subtract(least))
        }
        return total
    }
}
