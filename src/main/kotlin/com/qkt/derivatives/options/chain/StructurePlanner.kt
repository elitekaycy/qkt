package com.qkt.derivatives.options.chain

import com.qkt.common.Side
import com.qkt.derivatives.options.OptionLeg
import com.qkt.derivatives.options.OptionPayoff
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRight
import java.math.BigDecimal

/** One leg a structure asks for: [side] the [right] nearest [delta], in a days window, or (both null) the first leg's expiry. */
data class LegSpec(
    val side: Side,
    val right: OptionRight,
    val delta: Double,
    val minDays: Double?,
    val maxDays: Double?,
)

/** A selected leg: [side] the venue contract [contract] ([listing]) at its chain [mark]. */
data class PlannedLeg(
    val side: Side,
    val contract: String,
    val listing: OptionListing,
    val mark: BigDecimal,
)

/** What [StructurePlanner.plan] decided. */
sealed interface StructurePlan {
    /** Every leg selected; [maxLossPerUnit] is the worst expiry loss of one contract per leg, null when unbounded. */
    data class Ready(
        val legs: List<PlannedLeg>,
        val maxLossPerUnit: BigDecimal?,
    ) : StructurePlan

    /** The structure cannot be opened from this snapshot, for [reason]. */
    data class Refused(
        val reason: String,
    ) : StructurePlan
}

/**
 * Selects an option structure's legs from one chain snapshot ([OptionSelector]): the first leg by its
 * days window, every `SAME EXPIRY` leg in the first leg's expiry. Its maximum loss per unit (one
 * contract of [contractSize] per leg) is the legs' mark value less their minimum expiry payoff, as
 * the margin rule measures it.
 */
object StructurePlanner {
    /** Plans [specs] (the first naming a days window) on [snapshot]. */
    fun plan(
        specs: List<LegSpec>,
        snapshot: ChainSnapshot,
        listings: Map<String, OptionListing>,
        maxQuoteAgeMs: Long,
        contractSize: BigDecimal,
    ): StructurePlan {
        require(specs.isNotEmpty() && specs.first().minDays != null) { "a structure's first leg names a days window" }
        val legs = mutableListOf<PlannedLeg>()
        for ((index, spec) in specs.withIndex()) {
            val criteria =
                if (spec.minDays == null) {
                    LegCriteria(spec.right, spec.delta, expiryMs = legs.first().listing.expiryMs)
                } else {
                    LegCriteria(spec.right, spec.delta, spec.minDays, requireNotNull(spec.maxDays))
                }
            val picked =
                OptionSelector.select(snapshot, listings, criteria, maxQuoteAgeMs)
                    ?: return StructurePlan.Refused(
                        "leg ${index + 1} (${spec.side} ${spec.right} delta ${spec.delta}) selects no contract",
                    )
            legs += PlannedLeg(spec.side, picked.contract, listings.getValue(picked.contract), picked.quote.mark)
        }
        return StructurePlan.Ready(legs, maxLossPerUnit(legs, contractSize))
    }

    private fun maxLossPerUnit(
        legs: List<PlannedLeg>,
        contractSize: BigDecimal,
    ): BigDecimal? {
        val sign = { leg: PlannedLeg -> if (leg.side == Side.BUY) BigDecimal.ONE else BigDecimal.ONE.negate() }
        val value = legs.fold(BigDecimal.ZERO) { v, leg -> v.add(sign(leg).multiply(leg.mark).multiply(contractSize)) }
        val payoffLegs =
            legs.map { leg ->
                leg.listing.toContract().let { OptionLeg(it.right, it.strike, sign(leg), contractSize) }
            }
        val least = OptionPayoff.minimum(payoffLegs) ?: return null
        return value.subtract(least)
    }
}
