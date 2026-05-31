package com.qkt.indicators

import java.math.BigDecimal

/**
 * Read-only view of an indicator's latest value. Strategy code and the DSL binding
 * layer both consume this side of the interface; only the binding layer holds the
 * [Indicator] write side and pushes inputs in.
 *
 * `null` from [value] means "not yet computable" (insufficient history, hub gap).
 * Callers should treat null as a hard "skip this bar" rather than coalescing to zero.
 */
interface IndicatorOutput {
    /** Latest computed value, or `null` until [isReady] is true. */
    fun value(): BigDecimal?

    /** True once the indicator has received at least [warmupBars] samples. */
    val isReady: Boolean

    /** Number of inputs the indicator must observe before [value] returns non-null. */
    val warmupBars: Int
}

/**
 * Write side of [IndicatorOutput] — fed by the engine's binding layer on each tick or
 * candle close. Implementations are stateful and single-threaded; the engine drives
 * one indicator from one stream alias.
 *
 * Concrete catalog entries live under [com.qkt.indicators.catalog] and
 * [com.qkt.indicators.range]; the DSL surface is the [IndicatorRegistry][com.qkt.dsl.stdlib.IndicatorRegistry].
 */
interface Indicator<TIn> : IndicatorOutput {
    /** Feed one input to the indicator. Effects on [value] / [isReady] are immediate. */
    fun update(input: TIn)
}
