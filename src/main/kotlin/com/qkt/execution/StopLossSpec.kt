package com.qkt.execution

import java.math.BigDecimal

/**
 * The stop-loss leg of an [OrderRequest.Bracket]. Either a fixed absolute price
 * ([Fixed]) or an engine-managed armed trailing stop ([ArmedTrail]) that sits at
 * `entry ± distance` until MFE crosses [ArmedTrail.mfeThreshold], then trails at
 * the same distance from the running favorable extreme.
 *
 * Sealed for exhaustive dispatch in [com.qkt.app.OrderManager]. Future stop
 * variants (volatility-based, time-based, indicator-triggered) plug in as
 * additional sealed-class members; the `when` blocks the compiler surfaces them.
 */
sealed interface StopLossSpec {
    data class Fixed(
        val price: BigDecimal,
    ) : StopLossSpec {
        init {
            require(price.signum() > 0) { "Fixed stop price must be > 0: $price" }
        }
    }

    data class ArmedTrail(
        val trailDistance: BigDecimal,
        val mfeThreshold: BigDecimal,
    ) : StopLossSpec {
        init {
            require(trailDistance.signum() > 0) { "trailDistance must be > 0: $trailDistance" }
            require(mfeThreshold.signum() >= 0) { "mfeThreshold must be >= 0: $mfeThreshold" }
        }
    }
}
