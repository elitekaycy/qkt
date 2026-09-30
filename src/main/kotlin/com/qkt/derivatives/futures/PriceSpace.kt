package com.qkt.derivatives.futures

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The mapping between one contract's prices and its continuous series, at that contract's [shift]
 * (see [AdjustmentChain.shiftFor]). Levels sent to the venue are snapped to [tickSize] so that an
 * order never fills or triggers before the strategy's level is reached: buy limits round down, sell
 * limits up, buy stops up, sell stops down. A protective stop can therefore sit up to one tick wider
 * than asked, so risk sizing must use the snapped level.
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

    // Ratio arithmetic runs at 16 significant digits, so an on-grid price mapped out and back can
    // come back a hair off the grid (…99999). Rounding far below one tick before the directional
    // snap removes that noise without ever moving a genuinely off-grid level across a tick.
    private val noiseScale: Int = tickSize.stripTrailingZeros().scale().coerceAtLeast(0) + NOISE_DIGITS

    /** [raw] in the continuous series; fails when the series would reach zero or below. */
    fun toContinuous(raw: BigDecimal): BigDecimal = continuousPrice(adjustment, shift, raw)

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

    /**
     * A continuous-series [price] as the contract price it maps from, rounded half-even to the tick:
     * exact for every price [toContinuous] produced from an on-grid contract price.
     */
    fun priceToContract(price: BigDecimal): BigDecimal = snap(toRaw(price), RoundingMode.HALF_EVEN)

    private fun toRaw(level: BigDecimal): BigDecimal {
        val raw =
            when (adjustment) {
                PriceAdjustment.NONE -> level
                PriceAdjustment.PANAMA -> level.subtract(shift)
                PriceAdjustment.RATIO -> level.divide(shift, Money.CONTEXT)
            }
        require(raw.signum() > 0) { "level $level maps to contract price $raw, which is not positive" }
        return raw.setScale(noiseScale, RoundingMode.HALF_EVEN)
    }

    private fun snap(
        raw: BigDecimal,
        mode: RoundingMode,
    ): BigDecimal = raw.divide(tickSize, 0, mode).multiply(tickSize)

    private companion object {
        const val NOISE_DIGITS = 6
    }
}
