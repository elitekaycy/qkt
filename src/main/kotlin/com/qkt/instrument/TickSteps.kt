package com.qkt.instrument

import java.math.BigDecimal
import java.math.RoundingMode

/** Above prices of [above], the tick is [tick]. */
data class TickStep(
    val above: BigDecimal,
    val tick: BigDecimal,
)

/**
 * A price grid whose tick grows with the price, as on Deribit options (`tick_size_steps`): [base]
 * up to the first step's `above`, then each step's tick for prices strictly above it.
 */
data class TickSteps(
    val base: BigDecimal,
    val steps: List<TickStep> = emptyList(),
) {
    init {
        require(base.signum() > 0) { "TickSteps base tick must be > 0: $base" }
        require(steps.all { it.tick.signum() > 0 }) { "TickSteps every step's tick must be > 0: $steps" }
        require(
            steps.zipWithNext().all { (a, b) ->
                a.above < b.above
            },
        ) { "TickSteps steps must be ascending by price: $steps" }
    }

    /** The tick that applies at [price]. */
    fun tickAt(price: BigDecimal): BigDecimal = steps.lastOrNull { price > it.above }?.tick ?: base

    /** [price] rounded down onto the grid that applies there. */
    fun floor(price: BigDecimal): BigDecimal = snap(price, RoundingMode.FLOOR)

    /** [price] rounded up onto the grid that applies there. */
    fun ceil(price: BigDecimal): BigDecimal = snap(price, RoundingMode.CEILING)

    /** Whether [price] is a multiple of the tick that applies at it. */
    fun isOnGrid(price: BigDecimal): Boolean = price.remainder(tickAt(price)).signum() == 0

    private fun snap(
        price: BigDecimal,
        mode: RoundingMode,
    ): BigDecimal {
        val tick = tickAt(price)
        return price.divide(tick, 0, mode).multiply(tick)
    }
}
