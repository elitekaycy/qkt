package com.qkt.indicators

import java.math.BigDecimal

interface IndicatorOutput {
    fun value(): BigDecimal?

    val isReady: Boolean

    val warmupBars: Int
}

interface Indicator<TIn> : IndicatorOutput {
    fun update(input: TIn)
}
