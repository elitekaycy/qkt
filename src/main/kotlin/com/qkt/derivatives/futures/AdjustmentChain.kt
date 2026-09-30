package com.qkt.derivatives.futures

import com.qkt.common.Money
import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal

/**
 * Forward adjustment of a contract chain, anchored at the first contract: contract 0 keeps raw
 * prices and each later contract is shifted onto the series so far. Appending a roll never changes
 * an earlier contract's shift, which is what makes the series free of look-ahead.
 */
class AdjustmentChain(
    private val adjustment: PriceAdjustment,
    rolls: List<RollPrices>,
) {
    private val shifts: List<BigDecimal> =
        rolls.runningFold(identity()) { shift, roll ->
            when (adjustment) {
                PriceAdjustment.NONE -> shift
                PriceAdjustment.PANAMA -> shift.add(roll.fromPrice).subtract(roll.toPrice)
                PriceAdjustment.RATIO ->
                    shift.multiply(
                        roll.fromPrice.divide(roll.toPrice, Money.CONTEXT),
                        Money.CONTEXT,
                    )
            }
        }

    /** Number of contracts this chain covers. */
    val size: Int get() = shifts.size

    /** The shift of contract [index]: an additive offset (PANAMA), a factor (RATIO), or 0 (NONE). */
    fun shiftFor(index: Int): BigDecimal {
        require(
            index in shifts.indices,
        ) { "contract index $index is outside the adjusted chain (0..${shifts.lastIndex})" }
        return shifts[index]
    }

    /** [raw], a price of contract [index], in the continuous series; fails at zero or below. */
    fun toContinuous(
        index: Int,
        raw: BigDecimal,
    ): BigDecimal = continuousPrice(adjustment, shiftFor(index), raw)

    private fun identity(): BigDecimal = if (adjustment == PriceAdjustment.RATIO) BigDecimal.ONE else BigDecimal.ZERO
}
