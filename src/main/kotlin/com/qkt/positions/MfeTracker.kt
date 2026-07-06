package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * Tracks max favorable excursion (MFE) and max adverse excursion (MAE) of a single
 * position leg since [entryPrice].
 *
 * For a BUY leg, MFE = max(price - entry, 0) observed over all [onTick] calls.
 * For a SELL leg, MFE = max(entry - price, 0).
 *
 * MFE and MAE never decrease — they are high-water marks of profit and drawdown in
 * price units. Strategy authors read them via `POSITION.<stream>.mfe` and
 * `POSITION.<stream>.mae`; the stack engine reads them to decide when to fire
 * conditional stacks and recoil tiers.
 *
 * Construct one MfeTracker per [PositionLeg] you want to track. Discard it when the leg
 * closes — there's no reset semantic.
 */
class MfeTracker(
    private val side: Side,
    private val entryPrice: BigDecimal,
) {
    init {
        require(entryPrice.signum() > 0) { "entryPrice must be > 0: $entryPrice" }
    }

    @Volatile
    private var mfe: BigDecimal = BigDecimal.ZERO

    @Volatile
    private var mae: BigDecimal = BigDecimal.ZERO

    @Volatile
    private var adverseExtremePrice: BigDecimal? = null

    /**
     * Observe a new mid-price. Updates high-water marks if this tick is more favorable
     * or adverse than any previous tick. Idempotent for ties.
     */
    fun onTick(price: BigDecimal) {
        val favorable =
            when (side) {
                Side.BUY -> price.subtract(entryPrice)
                Side.SELL -> entryPrice.subtract(price)
            }
        if (favorable > mfe) {
            mfe = favorable
        }
        val adverse =
            when (side) {
                Side.BUY -> entryPrice.subtract(price)
                Side.SELL -> price.subtract(entryPrice)
            }
        if (adverse > mae) {
            mae = adverse
            adverseExtremePrice = price
        }
    }

    /** Current MFE — always ≥ 0. */
    fun value(): BigDecimal = mfe

    /** Current MAE — always ≥ 0. */
    fun mae(): BigDecimal = mae

    /** Price at the current worst adverse extreme, or null until MAE is greater than zero. */
    fun adverseExtremePrice(): BigDecimal? = adverseExtremePrice
}
