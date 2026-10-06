package com.qkt.derivatives.futures

import com.qkt.common.Money
import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal

/**
 * Adjustment of a contract chain, anchored at contract [anchor]: it keeps raw prices and every other
 * contract is shifted onto it. Anchored at the first contract (the default) the adjustment is forward:
 * each later contract is shifted onto the series so far, and appending a roll never changes an earlier
 * contract's shift, which is what makes the series free of look-ahead. A later anchor shifts the
 * contracts before it by the roll gaps up to it (backward adjustment); appending a roll after it still
 * changes no earlier shift.
 */
class AdjustmentChain(
    private val adjustment: PriceAdjustment,
    rolls: List<RollPrices>,
    anchor: Int = 0,
) {
    private val shifts: List<BigDecimal> = anchored(forward(rolls), anchor)

    private fun forward(rolls: List<RollPrices>): List<BigDecimal> =
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

    /** [forward] shifts re-based so contract [anchor] keeps raw prices; unchanged for the first contract. */
    private fun anchored(
        forward: List<BigDecimal>,
        anchor: Int,
    ): List<BigDecimal> {
        require(
            anchor in forward.indices,
        ) { "anchor index $anchor is outside the adjusted chain (0..${forward.lastIndex})" }
        if (anchor == 0) return forward
        val base = forward[anchor]
        return when (adjustment) {
            PriceAdjustment.NONE -> forward
            PriceAdjustment.PANAMA -> forward.map { it.subtract(base) }
            PriceAdjustment.RATIO -> forward.map { it.divide(base, Money.CONTEXT) }
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
