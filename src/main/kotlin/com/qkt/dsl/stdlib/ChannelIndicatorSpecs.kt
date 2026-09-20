package com.qkt.dsl.stdlib

import com.qkt.indicators.Indicator
import com.qkt.indicators.catalog.ADX
import com.qkt.indicators.catalog.BollingerBands
import com.qkt.indicators.catalog.KeltnerChannels
import com.qkt.indicators.catalog.MACD
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Registry entries for multi-output channel and line indicators: Keltner Channels,
 * the ADX family, MACD and Bollinger Bands. Each DSL name builds its own underlying
 * instance behind a thin wrapper that exposes one output.
 */
internal val channelIndicatorSpecs: List<Pair<String, IndicatorSpec>> =
    listOf(
        // ---- Keltner Channels (three outputs, candle) ----
        "KELTNER_UPPER" to
            IndicatorSpec("KELTNER_UPPER", IndicatorInput.CANDLE_SERIES, arity = 3) { args ->
                val k = KeltnerChannels(period = args[0].toInt(), atrMult = args[1])
                object : Indicator<Candle> {
                    override fun update(input: Candle) = k.update(input)

                    override fun value(): BigDecimal? = k.bands()?.upper

                    override val isReady: Boolean get() = k.isReady
                    override val warmupBars: Int = k.warmupBars
                }
            },
        "KELTNER_MIDDLE" to
            IndicatorSpec("KELTNER_MIDDLE", IndicatorInput.CANDLE_SERIES, arity = 3) { args ->
                val k = KeltnerChannels(period = args[0].toInt(), atrMult = args[1])
                object : Indicator<Candle> {
                    override fun update(input: Candle) = k.update(input)

                    override fun value(): BigDecimal? = k.bands()?.middle

                    override val isReady: Boolean get() = k.isReady
                    override val warmupBars: Int = k.warmupBars
                }
            },
        "KELTNER_LOWER" to
            IndicatorSpec("KELTNER_LOWER", IndicatorInput.CANDLE_SERIES, arity = 3) { args ->
                val k = KeltnerChannels(period = args[0].toInt(), atrMult = args[1])
                object : Indicator<Candle> {
                    override fun update(input: Candle) = k.update(input)

                    override fun value(): BigDecimal? = k.bands()?.lower

                    override val isReady: Boolean get() = k.isReady
                    override val warmupBars: Int = k.warmupBars
                }
            },
        // ---- directional movement (three outputs, candle) ----
        "PLUS_DI" to
            IndicatorSpec("PLUS_DI", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                val a = ADX(period = args[0].toInt())
                object : Indicator<Candle> {
                    override fun update(input: Candle) = a.update(input)

                    override fun value(): BigDecimal? = a.lines()?.plusDi

                    override val isReady: Boolean get() = a.isReady
                    override val warmupBars: Int = a.warmupBars
                }
            },
        "MINUS_DI" to
            IndicatorSpec("MINUS_DI", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                val a = ADX(period = args[0].toInt())
                object : Indicator<Candle> {
                    override fun update(input: Candle) = a.update(input)

                    override fun value(): BigDecimal? = a.lines()?.minusDi

                    override val isReady: Boolean get() = a.isReady
                    override val warmupBars: Int = a.warmupBars
                }
            },
        "ADX" to
            IndicatorSpec("ADX", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                val a = ADX(period = args[0].toInt())
                object : Indicator<Candle> {
                    override fun update(input: Candle) = a.update(input)

                    override fun value(): BigDecimal? = a.lines()?.adx

                    override val isReady: Boolean get() = a.isReady
                    override val warmupBars: Int = a.warmupBars
                }
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
    )
