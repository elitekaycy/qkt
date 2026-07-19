package com.qkt.dsl.compile

import com.qkt.common.Money
import java.math.BigDecimal

/** Shared whole-percent conversion for every bracket execution path. */
internal object BracketPercent {
    private val oneHundred = BigDecimal("100")
    private val minPercent = BigDecimal("0.01")
    private val maxStopPercent = BigDecimal("50")

    fun fraction(
        percent: BigDecimal,
        isStopLoss: Boolean,
    ): BigDecimal {
        require(percent.signum() > 0) { "bracket PCT must be greater than 0, was $percent" }
        require(percent >= minPercent) {
            "bracket PCT $percent is below the minimum $minPercent percentage points; " +
                "PCT uses percentage points (0.4 means 0.4%), not fractions"
        }
        if (isStopLoss) {
            require(percent < maxStopPercent) {
                "STOP LOSS PCT must be less than $maxStopPercent, was $percent"
            }
        }
        return percent.divide(oneHundred, Money.CONTEXT)
    }
}
