package com.qkt.common

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object Money {
    val CONTEXT: MathContext = MathContext.DECIMAL64
    const val SCALE: Int = 8
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(SCALE, ROUNDING)

    fun of(value: String): BigDecimal = BigDecimal(value).setScale(SCALE, ROUNDING)

    fun of(value: Long): BigDecimal = BigDecimal.valueOf(value).setScale(SCALE, ROUNDING)

    fun of(value: Int): BigDecimal = BigDecimal.valueOf(value.toLong()).setScale(SCALE, ROUNDING)
}
