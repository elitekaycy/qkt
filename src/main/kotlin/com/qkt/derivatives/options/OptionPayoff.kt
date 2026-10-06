package com.qkt.derivatives.options

import com.qkt.common.Money
import com.qkt.instrument.OptionRight
import java.math.BigDecimal

/** One position in a set of options of one expiry: [quantity] signed (short negative) of [contractSize] units. */
data class OptionLeg(
    val right: OptionRight,
    val strike: BigDecimal,
    val quantity: BigDecimal,
    val contractSize: BigDecimal,
)

/** What European options pay at expiry. */
object OptionPayoff {
    /** The intrinsic value of a [right] struck at [strike] with the underlying settling at [settle]: `max(S − K, 0)` or `max(K − S, 0)`. */
    fun intrinsic(
        right: OptionRight,
        strike: BigDecimal,
        settle: BigDecimal,
    ): BigDecimal =
        when (right) {
            OptionRight.CALL -> settle.subtract(strike)
            OptionRight.PUT -> strike.subtract(settle)
        }.max(BigDecimal.ZERO).setScale(Money.SCALE, Money.ROUNDING)

    /**
     * The least the [legs] (one expiry) pay together at expiry over every settlement price S ≥ 0, or
     * null when that is unbounded below. The payoff is piecewise linear with kinks at the strikes, so
     * its minimum is at S = 0 or at a strike, unless its slope above the highest strike (the net call
     * quantity) is negative.
     */
    fun minimum(legs: List<OptionLeg>): BigDecimal? {
        val callSlope =
            legs
                .filter {
                    it.right == OptionRight.CALL
                }.fold(BigDecimal.ZERO) { s, l -> s.add(l.quantity.multiply(l.contractSize)) }
        if (callSlope.signum() < 0) return null
        return (listOf(BigDecimal.ZERO) + legs.map { it.strike }).minOf { settle -> at(legs, settle) }
    }

    private fun at(
        legs: List<OptionLeg>,
        settle: BigDecimal,
    ): BigDecimal =
        legs.fold(BigDecimal.ZERO) { total, l ->
            total.add(intrinsic(l.right, l.strike, settle).multiply(l.quantity).multiply(l.contractSize))
        }
}
