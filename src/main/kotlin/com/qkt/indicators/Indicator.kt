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

/**
 * Write side for indicators that consume **two** aligned numeric series — e.g. correlation or
 * beta between two instruments. The binding layer feeds one `(a, b)` pair per bar, the latest
 * values of the two series. Read side is the shared [IndicatorOutput].
 * e.g. `CORRELATION(gold.close, silver.close, 60)` → `update(gold, silver)` once per bar.
 */
interface BiIndicator : IndicatorOutput {
    /** Feed one aligned pair. Effects on [value] / [isReady] are immediate. */
    fun update(
        a: BigDecimal,
        b: BigDecimal,
    )
}

/**
 * Write side for indicators that consume **k** aligned numeric series at once — e.g. a
 * multi-regressor regression residual. The binding layer feeds one aligned tuple per bar,
 * the latest values of every series in a fixed order. Read side is the shared [IndicatorOutput].
 * e.g. `RESID(gbp.close, eur.close, aud.close, 96)` → `update([gbp, eur, aud])` once per bar,
 * the dependent value first.
 */
interface MultiIndicator : IndicatorOutput {
    /** Feed one aligned tuple of series values (dependent first, then regressors). */
    fun update(values: List<BigDecimal>)
}
