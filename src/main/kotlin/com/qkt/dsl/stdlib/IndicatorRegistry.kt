package com.qkt.dsl.stdlib

import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import com.qkt.indicators.catalog.ATR
import com.qkt.indicators.catalog.BollingerBands
import com.qkt.indicators.catalog.EMA
import com.qkt.indicators.catalog.MACD
import com.qkt.indicators.catalog.RSI
import com.qkt.indicators.catalog.RollingHigh
import com.qkt.indicators.catalog.RollingLow
import com.qkt.indicators.catalog.SMA
import com.qkt.indicators.catalog.WMA
import java.math.BigDecimal

enum class IndicatorInput { NUMERIC_SERIES, CANDLE_SERIES }

data class IndicatorSpec(
    val name: String,
    val inputKind: IndicatorInput,
    val arity: Int,
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

    fun has(name: String): Boolean = table.containsKey(name)

    fun spec(name: String): IndicatorSpec? = table[name]

    fun create(
        name: String,
        constArgs: List<BigDecimal>,
    ): IndicatorOutput {
        val s = spec(name) ?: error("Unknown indicator: $name")
        require(constArgs.size == s.arity - 1) {
            "Indicator $name expects ${s.arity - 1} constant args, got ${constArgs.size}"
        }
        return s.factory(constArgs)
    }
}
