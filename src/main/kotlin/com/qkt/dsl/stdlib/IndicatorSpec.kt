package com.qkt.dsl.stdlib

import com.qkt.indicators.IndicatorOutput
import java.math.BigDecimal

/**
 * Source-of-data shape an indicator expects.
 *
 * - [NUMERIC_SERIES] — single `BigDecimal` per closed candle (close, open, …).
 * - [CANDLE_SERIES] — the whole closed candle (e.g. ATR needs all of OHLC).
 * - [BOOLEAN_SERIES] — single `Boolean` per closed candle, from a condition expression.
 * - [TICK_SERIES] — every raw tick, not just candle-close values (e.g. VWAP).
 *   The DSL exposes this via the synthetic `<alias>.tick` series argument.
 */
enum class IndicatorInput {
    NUMERIC_SERIES,
    CANDLE_SERIES,
    BOOLEAN_SERIES,
    TICK_SERIES,
}

/**
 * One registered DSL indicator: its uppercase [name], the [inputKind] it consumes, its call
 * [arity] including the series arguments, and the [factory] that builds a runtime instance
 * from the constant arguments.
 */
data class IndicatorSpec(
    val name: String,
    val inputKind: IndicatorInput,
    val arity: Int,
    /** Number of leading series args (1 for normal indicators, 2 for two-series like CORRELATION). */
    val seriesCount: Int = 1,
    /** True for volume-weighted indicators (VWAP, OBV) — the bound feed must supply volume. */
    val requiresVolume: Boolean = false,
    val factory: (List<BigDecimal>) -> IndicatorOutput,
)
