package com.qkt.dsl.compile

import com.qkt.common.Money
import java.math.BigDecimal

/** Shared whole-percent conversion for every bracket execution path. */
internal object BracketPercent {
    private val oneHundred = BigDecimal("100")
    private val maxStopPercent = BigDecimal("50")

    fun fraction(
        percent: BigDecimal,
        isStopLoss: Boolean,
    ): BigDecimal {
        require(percent.signum() > 0) { "bracket PCT must be greater than 0, was $percent" }
        if (isStopLoss) {
            require(percent < maxStopPercent) {
                "STOP LOSS PCT must be less than $maxStopPercent, was $percent"
            }
        }
        return percent.divide(oneHundred, Money.CONTEXT)
    }
}
