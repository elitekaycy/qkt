package com.qkt.execution

import java.math.BigDecimal

/**
 * The stop-loss leg of an [OrderRequest.Bracket]. Either a fixed absolute price
 * ([Fixed]) or an engine-managed stop policy. Engine-managed policies begin at
 * `entry ± initial distance` and only move toward profit.
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

    /** Direction-relative target applied once [mfeThreshold] has been crossed. */
    data class Step(
        val mfeThreshold: BigDecimal,
        val profitDistance: BigDecimal,
    ) {
        init {
            require(mfeThreshold.signum() >= 0) { "mfeThreshold must be >= 0: $mfeThreshold" }
            require(profitDistance.signum() >= 0) { "profitDistance must be >= 0: $profitDistance" }
        }
    }

    /**
     * Stop that consumes [steps] in ascending MFE order. Each target is
     * `entry ± profitDistance`, with the sign chosen for the entry direction.
     */
    data class SteppedStop(
        val initialDistance: BigDecimal,
        val steps: List<Step>,
    ) : StopLossSpec {
        init {
            require(initialDistance.signum() > 0) { "initialDistance must be > 0: $initialDistance" }
            require(steps.isNotEmpty()) { "stepped stop must contain at least one step" }
            for (index in 1 until steps.size) {
                require(steps[index].mfeThreshold > steps[index - 1].mfeThreshold) {
                    "step MFE thresholds must be strictly increasing"
                }
            }
        }
    }

    /** Stop whose distance decays by [tightenBy] every [intervalMs] down to [floorDistance]. */
    data class TimeTighten(
        val initialDistance: BigDecimal,
        val tightenBy: BigDecimal,
        val intervalMs: Long,
        val floorDistance: BigDecimal,
    ) : StopLossSpec {
        init {
            require(initialDistance.signum() > 0) { "initialDistance must be > 0: $initialDistance" }
            require(tightenBy.signum() > 0) { "tightenBy must be > 0: $tightenBy" }
            require(intervalMs > 0) { "intervalMs must be > 0: $intervalMs" }
            require(floorDistance.signum() > 0) { "floorDistance must be > 0: $floorDistance" }
            require(floorDistance <= initialDistance) {
                "floorDistance must be <= initialDistance: $floorDistance > $initialDistance"
            }
        }
    }
}
