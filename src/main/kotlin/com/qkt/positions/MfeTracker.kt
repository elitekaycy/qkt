package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * Tracks max favorable excursion (MFE) of a single position leg since [entryPrice].
 *
 * For a BUY leg, MFE = max(price - entry, 0) observed over all [onTick] calls.
 * For a SELL leg, MFE = max(entry - price, 0).
 *
 * MFE never decreases — it's a high-water mark of profit in price units. Strategy
 * authors read MFE via the DSL accessor `POSITION.<stream>.mfe` (Phase 27); the stack
 * engine reads it via [value] every tick to decide when to fire conditional stacks.
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

    /**
     * Observe a new mid-price. Updates the high-water mark if this tick is more
     * favorable than any previous tick. Idempotent for ties or worse prices.
     */
    fun onTick(price: BigDecimal) {
        val excursion =
            when (side) {
                Side.BUY -> price.subtract(entryPrice)
                Side.SELL -> entryPrice.subtract(price)
            }
        if (excursion > mfe) {
            mfe = excursion
        }
    }

    /** Current MFE — always ≥ 0. */
    fun value(): BigDecimal = mfe
}
