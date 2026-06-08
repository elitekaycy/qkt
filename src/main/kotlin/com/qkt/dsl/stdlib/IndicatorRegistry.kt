package com.qkt.dsl.stdlib

import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import com.qkt.indicators.catalog.ATR
import com.qkt.indicators.catalog.Beta
import com.qkt.indicators.catalog.BollingerBands
import com.qkt.indicators.catalog.CCI
import com.qkt.indicators.catalog.Correlation
import com.qkt.indicators.catalog.DEMA
import com.qkt.indicators.catalog.EMA
import com.qkt.indicators.catalog.HMA
import com.qkt.indicators.catalog.MACD
import com.qkt.indicators.catalog.OBV
import com.qkt.indicators.catalog.RSI
import com.qkt.indicators.catalog.RegressionSlope
import com.qkt.indicators.catalog.RollingHigh
import com.qkt.indicators.catalog.RollingLow
import com.qkt.indicators.catalog.SMA
import com.qkt.indicators.catalog.Stddev
import com.qkt.indicators.catalog.Stochastic
import com.qkt.indicators.catalog.TEMA
import com.qkt.indicators.catalog.VWAP
import com.qkt.indicators.catalog.Variance
import com.qkt.indicators.catalog.WMA
import com.qkt.indicators.catalog.WilliamsR
import com.qkt.indicators.catalog.ZScore
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Source-of-data shape an indicator expects.
 *
 * - [NUMERIC_SERIES] — single `BigDecimal` per closed candle (close, open, …).
 * - [CANDLE_SERIES] — the whole closed candle (e.g. ATR needs all of OHLC).
 * - [TICK_SERIES] — every raw tick, not just candle-close values (e.g. VWAP).
 *   The DSL exposes this via the synthetic `<alias>.tick` series argument.
 */
enum class IndicatorInput { NUMERIC_SERIES, CANDLE_SERIES, TICK_SERIES }

data class IndicatorSpec(
    val name: String,
    val inputKind: IndicatorInput,
    val arity: Int,
    /** Number of leading series args (1 for normal indicators, 2 for two-series like CORRELATION). */
    val seriesCount: Int = 1,
    val factory: (List<BigDecimal>) -> IndicatorOutput,
)

/**
 * Maps DSL indicator names to factory functions that build runtime instances.
 *
 * Each [IndicatorSpec] carries the indicator's DSL name (uppercase), the input
 * kind it consumes (numeric series or candle series), the call arity in the
 * DSL (including the leading value/stream argument), and a factory that takes
 * the remaining constant arguments and constructs the indicator.
 *
 * MACD and Bollinger Bands are multi-output indicators. Each output gets its
 * own DSL name (`MACD`, `MACD_SIGNAL`, `MACD_HIST` / `BOLLINGER_UPPER`,
 * `BOLLINGER_MIDDLE`, `BOLLINGER_LOWER`) and constructs a thin
 * [Indicator]<[BigDecimal]> wrapper that delegates to one underlying instance
 * and exposes the desired output.
 */
object IndicatorRegistry {
    private val table: Map<String, IndicatorSpec> =
        mapOf(
            // ---- moving averages ----
            "EMA" to
                IndicatorSpec("EMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    EMA(period = args[0].toInt())
                },
            "SMA" to
                IndicatorSpec("SMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    SMA(period = args[0].toInt())
                },
            "WMA" to
                IndicatorSpec("WMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    WMA(period = args[0].toInt())
                },
            "DEMA" to
                IndicatorSpec("DEMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    DEMA(period = args[0].toInt())
                },
            "TEMA" to
                IndicatorSpec("TEMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    TEMA(period = args[0].toInt())
                },
            "HMA" to
                IndicatorSpec("HMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    HMA(period = args[0].toInt())
                },
            // ---- oscillators ----
            "RSI" to
                IndicatorSpec("RSI", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    RSI(period = args[0].toInt())
                },
            // ---- volatility ----
            "ATR" to
                IndicatorSpec("ATR", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    ATR(period = args[0].toInt())
                },
            "STDDEV" to
                IndicatorSpec("STDDEV", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    Stddev(period = args[0].toInt())
                },
            "VARIANCE" to
                IndicatorSpec("VARIANCE", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    Variance(period = args[0].toInt())
                },
            // ---- statistical ----
            "ZSCORE" to
                IndicatorSpec("ZSCORE", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    ZScore(period = args[0].toInt())
                },
            "REGRESSION_SLOPE" to
                IndicatorSpec("REGRESSION_SLOPE", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    RegressionSlope(period = args[0].toInt())
                },
            // ---- cross-series (two-input) ----
            "CORRELATION" to
                IndicatorSpec("CORRELATION", IndicatorInput.NUMERIC_SERIES, arity = 3, seriesCount = 2) { args ->
                    Correlation(period = args[0].toInt())
                },
            "BETA" to
                IndicatorSpec("BETA", IndicatorInput.NUMERIC_SERIES, arity = 3, seriesCount = 2) { args ->
                    Beta(period = args[0].toInt())
                },
            // ---- candle-fed oscillators ----
            "WILLIAMS_R" to
                IndicatorSpec("WILLIAMS_R", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    WilliamsR(period = args[0].toInt())
                },
            "CCI" to
                IndicatorSpec("CCI", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    CCI(period = args[0].toInt())
                },
            "STOCH_K" to
                IndicatorSpec("STOCH_K", IndicatorInput.CANDLE_SERIES, arity = 3) { args ->
                    val s = Stochastic(kPeriod = args[0].toInt(), dPeriod = args[1].toInt())
                    object : Indicator<Candle> {
                        override fun update(input: Candle) = s.update(input)

                        override fun value(): BigDecimal? = s.lines()?.k

                        override val isReady: Boolean get() = s.isReady
                        override val warmupBars: Int = s.warmupBars
                    }
                },
            "STOCH_D" to
                IndicatorSpec("STOCH_D", IndicatorInput.CANDLE_SERIES, arity = 3) { args ->
                    val s = Stochastic(kPeriod = args[0].toInt(), dPeriod = args[1].toInt())
                    object : Indicator<Candle> {
                        override fun update(input: Candle) = s.update(input)

                        override fun value(): BigDecimal? = s.lines()?.d

                        override val isReady: Boolean get() = s.isReady
                        override val warmupBars: Int = s.warmupBars
                    }
                },
            // ---- volume ----
            "OBV" to
                IndicatorSpec("OBV", IndicatorInput.CANDLE_SERIES, arity = 1) { OBV() },
            // ---- MACD (three outputs) ----
            "MACD" to
                IndicatorSpec("MACD", IndicatorInput.NUMERIC_SERIES, arity = 4) { args ->
                    MACD(fast = args[0].toInt(), slow = args[1].toInt(), signal = args[2].toInt())
                },
            "MACD_SIGNAL" to
                IndicatorSpec("MACD_SIGNAL", IndicatorInput.NUMERIC_SERIES, arity = 4) { args ->
                    val m = MACD(fast = args[0].toInt(), slow = args[1].toInt(), signal = args[2].toInt())
                    object : Indicator<BigDecimal> {
                        override fun update(input: BigDecimal) = m.update(input)

                        override fun value(): BigDecimal? = m.lines()?.signal

                        override val isReady: Boolean get() = m.isReady
                        override val warmupBars: Int = m.warmupBars
                    }
                },
            "MACD_HIST" to
                IndicatorSpec("MACD_HIST", IndicatorInput.NUMERIC_SERIES, arity = 4) { args ->
                    val m = MACD(fast = args[0].toInt(), slow = args[1].toInt(), signal = args[2].toInt())
                    object : Indicator<BigDecimal> {
                        override fun update(input: BigDecimal) = m.update(input)

                        override fun value(): BigDecimal? = m.lines()?.histogram

                        override val isReady: Boolean get() = m.isReady
                        override val warmupBars: Int = m.warmupBars
                    }
                },
            // ---- Bollinger Bands (three outputs) ----
            "BOLLINGER_UPPER" to
                IndicatorSpec("BOLLINGER_UPPER", IndicatorInput.NUMERIC_SERIES, arity = 3) { args ->
                    val b = BollingerBands(period = args[0].toInt(), stddevK = args[1].toDouble())
                    object : Indicator<BigDecimal> {
                        override fun update(input: BigDecimal) = b.update(input)

                        override fun value(): BigDecimal? = b.bands()?.upper

                        override val isReady: Boolean get() = b.isReady
                        override val warmupBars: Int = b.warmupBars
                    }
                },
            "BOLLINGER_MIDDLE" to
                IndicatorSpec("BOLLINGER_MIDDLE", IndicatorInput.NUMERIC_SERIES, arity = 3) { args ->
                    val b = BollingerBands(period = args[0].toInt(), stddevK = args[1].toDouble())
                    object : Indicator<BigDecimal> {
                        override fun update(input: BigDecimal) = b.update(input)

                        override fun value(): BigDecimal? = b.bands()?.middle

                        override val isReady: Boolean get() = b.isReady
                        override val warmupBars: Int = b.warmupBars
                    }
                },
            "BOLLINGER_LOWER" to
                IndicatorSpec("BOLLINGER_LOWER", IndicatorInput.NUMERIC_SERIES, arity = 3) { args ->
                    val b = BollingerBands(period = args[0].toInt(), stddevK = args[1].toDouble())
                    object : Indicator<BigDecimal> {
                        override fun update(input: BigDecimal) = b.update(input)

                        override fun value(): BigDecimal? = b.bands()?.lower

                        override val isReady: Boolean get() = b.isReady
                        override val warmupBars: Int = b.warmupBars
                    }
                },
            // ---- Volume-weighted average price (tick-fed) ----
            "VWAP" to
                IndicatorSpec("VWAP", IndicatorInput.TICK_SERIES, arity = 2) { args ->
                    VWAP(period = args[0].toInt())
                },
            // ---- Donchian rolling extremes ----
            "HIGHEST" to
                IndicatorSpec("HIGHEST", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    RollingHigh(period = args[0].toInt())
                },
            "LOWEST" to
                IndicatorSpec("LOWEST", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    RollingLow(period = args[0].toInt())
                },
        )

    fun has(name: String): Boolean = table.containsKey(name.uppercase())

    fun spec(name: String): IndicatorSpec? = table[name.uppercase()]

    fun create(
        name: String,
        constArgs: List<BigDecimal>,
    ): IndicatorOutput {
        val s = spec(name) ?: error("Unknown indicator: $name")
        val expectedConst = s.arity - s.seriesCount
        require(constArgs.size == expectedConst) {
            "Indicator $name expects $expectedConst constant args, got ${constArgs.size}"
        }
        return s.factory(constArgs)
    }
}
