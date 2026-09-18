package com.qkt.dsl.stdlib

import com.qkt.indicators.Indicator
import com.qkt.indicators.catalog.CCI
import com.qkt.indicators.catalog.OBV
import com.qkt.indicators.catalog.Stochastic
import com.qkt.indicators.catalog.WilliamsR
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Registry entries for candle-fed oscillators (Williams %R, CCI, the two Stochastic
 * lines) and on-balance volume. Each Stochastic line wraps its own [Stochastic] instance
 * and exposes one output.
 */
internal val candleOscillatorSpecs: List<Pair<String, IndicatorSpec>> =
    listOf(
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
            IndicatorSpec("OBV", IndicatorInput.CANDLE_SERIES, arity = 1, requiresVolume = true) { OBV() },
    )
