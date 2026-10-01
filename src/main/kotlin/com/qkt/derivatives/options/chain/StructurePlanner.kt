package com.qkt.derivatives.options.chain

import com.qkt.common.Side
import com.qkt.derivatives.options.ExpiringLeg
import com.qkt.derivatives.options.OptionLeg
import com.qkt.derivatives.options.StructureRisk
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
 * Selects an option structure's legs from one chain snapshot ([OptionSelector]): each leg by its own
 * days window, a `SAME EXPIRY` leg in the first leg's expiry. Its maximum loss per unit (one contract
 * of [contractSize] per leg) is [StructureRisk.maxLoss] at the legs' marks, per expiry as the margin
 * rule measures it. Two legs selecting one contract refuse the plan: a structure holds each contract
 * once (ratios are not structures here).
 */
object StructurePlanner {
    /** Plans [specs] (the first naming a days window) on [snapshot]. */
    fun plan(
        specs: List<LegSpec>,
        snapshot: ChainSnapshot,
        listings: Map<String, OptionListing>,
        maxQuoteAgeMs: Long,
        contractSize: BigDecimal,
        nowMs: Long,
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
                OptionSelector.select(snapshot, listings, criteria, maxQuoteAgeMs, nowMs)
                    ?: return StructurePlan.Refused(
                        "leg ${index + 1} (${spec.side} ${spec.right} delta ${spec.delta}) selects no contract",
                    )
            val earlier = legs.indexOfFirst { it.contract == picked.contract }
            if (earlier >= 0) {
                return StructurePlan.Refused(
                    "legs ${earlier + 1} and ${index + 1} select the same contract ${picked.contract}",
                )
            }
            legs += PlannedLeg(spec.side, picked.contract, listings.getValue(picked.contract), picked.quote.mark)
        }
        return StructurePlan.Ready(legs, maxLossPerUnit(legs, contractSize))
    }

    private fun maxLossPerUnit(
        legs: List<PlannedLeg>,
        contractSize: BigDecimal,
    ): BigDecimal? =
        StructureRisk.maxLoss(
            legs.map { leg ->
                val sign = if (leg.side == Side.BUY) BigDecimal.ONE else BigDecimal.ONE.negate()
                val contract = leg.listing.toContract()
                ExpiringLeg(
                    OptionLeg(contract.right, contract.strike, sign, contractSize),
                    leg.listing.expiryMs,
                    leg.mark,
                )
            },
        )
}
