package com.qkt.derivatives.futures

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The mapping between one contract's prices and its continuous series, at that contract's [shift]
 * (see [AdjustmentChain.shiftFor]). Levels sent to the venue are snapped to [tickSize] in the
 * direction that never gives the order a better price than the strategy asked for.
 */
class PriceSpace(
    private val adjustment: PriceAdjustment,
    private val shift: BigDecimal,
    private val tickSize: BigDecimal,
) {
    init {
        require(tickSize.signum() > 0) { "PriceSpace.tickSize must be > 0: $tickSize" }
        require(adjustment != PriceAdjustment.RATIO || shift.signum() > 0) { "ratio factor must be > 0: $shift" }
    }

    /** [raw] in the continuous series; fails when the series would reach zero or below. */
    fun toContinuous(raw: BigDecimal): BigDecimal {
        val continuous =
            when (adjustment) {
                PriceAdjustment.NONE -> raw
                PriceAdjustment.PANAMA -> raw.add(shift)
                PriceAdjustment.RATIO -> raw.multiply(shift, Money.CONTEXT)
            }
        require(continuous.signum() > 0) {
            "continuous price $continuous (raw $raw) is not positive; use 'adjust: ratio' for this root"
        }
        return continuous
    }

    /** A limit [level] in the continuous series as a contract price: buys round down, sells up. */
    fun limitToContract(
        level: BigDecimal,
        side: Side,
    ): BigDecimal = snap(toRaw(level), if (side == Side.BUY) RoundingMode.FLOOR else RoundingMode.CEILING)

    /** A stop [level] in the continuous series as a contract price: buys round up, sells down. */
    fun stopToContract(
        level: BigDecimal,
        side: Side,
    ): BigDecimal = snap(toRaw(level), if (side == Side.BUY) RoundingMode.CEILING else RoundingMode.FLOOR)

    /** A price [distance] in the continuous series as a contract-price distance. */
    fun distanceToContract(distance: BigDecimal): BigDecimal =
        if (adjustment == PriceAdjustment.RATIO) distance.divide(shift, Money.CONTEXT) else distance

    private fun toRaw(level: BigDecimal): BigDecimal =
        when (adjustment) {
            PriceAdjustment.NONE -> level
            PriceAdjustment.PANAMA -> level.subtract(shift)
            PriceAdjustment.RATIO -> level.divide(shift, Money.CONTEXT)
        }

    private fun snap(
        raw: BigDecimal,
        mode: RoundingMode,
    ): BigDecimal = raw.divide(tickSize, 0, mode).multiply(tickSize)
}
