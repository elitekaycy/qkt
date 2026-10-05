package com.qkt.derivatives.options.chain

import com.qkt.derivatives.options.pricing.Black76
import com.qkt.derivatives.options.pricing.OptionValue
import com.qkt.instrument.OptionContract
import com.qkt.instrument.OptionListing
import java.math.BigDecimal

/** [quantity] (signed) of the venue [contract], of [contractSize] units of the underlying each. */
data class HeldLeg(
    val contract: String,
    val quantity: BigDecimal,
    val contractSize: BigDecimal,
)

/**
 * A position's sensitivities in trader units: [delta] in units of the underlying, [gamma] in units of
 * the underlying per unit of its price, [vega] in the quote currency per volatility point, [theta] in
 * the quote currency per calendar day.
 */
data class PositionGreeks(
    val delta: Double,
    val gamma: Double,
    val vega: Double,
    val theta: Double,
)

/**
 * Black-76 Greeks of held option legs, summed as quantity × contract size × Greek. Each leg is valued
 * from one snapshot: its own quote's mark IV, its expiry's forward (the median `underlying` of the
 * usable quotes, as the selector uses), rate 0, and time to expiry from the clock. Vega is scaled from
 * per 1.00 to per volatility point (÷ 100) and theta from per year to per day (÷ 365, the pricing year).
 */
object StructureGreeks {
    private const val DAY_MS = 86_400_000.0
    private const val YEAR_MS = 365 * DAY_MS
    private const val DAYS_PER_YEAR = 365.0
    private const val VOL_POINTS = 100.0

    /**
     * The Greeks of [legs] at [nowMs] from [snapshot]; null when there is no leg, or a leg has no
     * usable quote (catalogued, IV > 0, no older than [maxQuoteAgeMs]) or has expired at [nowMs]:
     * undefined, never 0.
     */
    fun of(
        legs: List<HeldLeg>,
        snapshot: ChainSnapshot,
        listings: Map<String, OptionListing>,
        maxQuoteAgeMs: Long,
        nowMs: Long,
    ): PositionGreeks? {
        if (legs.isEmpty()) return null
        val usable = usableQuotes(snapshot, listings, maxQuoteAgeMs, nowMs)
        val byContract = usable.associateBy { it.contract }
        val byExpiry = usable.groupBy { listings.getValue(it.contract).expiryMs }
        var total = PositionGreeks(0.0, 0.0, 0.0, 0.0)
        for (leg in legs) {
            val quote = byContract[leg.contract] ?: return null
            val contract = listings.getValue(leg.contract).toContract()
            val forward = medianForward(byExpiry.getValue(contract.expiryMs))
            val value = black76(contract, forward, requireNotNull(quote.markIv).toDouble(), nowMs)
            val units = leg.quantity.multiply(leg.contractSize).toDouble()
            total =
                PositionGreeks(
                    total.delta + units * value.delta,
                    total.gamma + units * value.gamma,
                    total.vega + units * value.vega / VOL_POINTS,
                    total.theta + units * value.theta / DAYS_PER_YEAR,
                )
        }
        return total
    }

    /**
     * One unit of the underlying's worth of [contract] at [nowMs] (before its expiry): Black-76 on [forward] and
     * [markIv] (volatility points) at rate 0, in [PositionGreeks]' units.
     */
    fun perUnit(
        contract: OptionContract,
        forward: Double,
        markIv: Double,
        nowMs: Long,
    ): PositionGreeks {
        val value = black76(contract, forward, markIv, nowMs)
        return PositionGreeks(value.delta, value.gamma, value.vega / VOL_POINTS, value.theta / DAYS_PER_YEAR)
    }

    private fun black76(
        contract: OptionContract,
        forward: Double,
        markIv: Double,
        nowMs: Long,
    ): OptionValue {
        val years = (contract.expiryMs - nowMs) / YEAR_MS
        return Black76.value(contract.right, forward, contract.strike.toDouble(), years, 0.0, markIv / VOL_POINTS)
    }
}
