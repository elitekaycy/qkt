package com.qkt.dsl.stdlib

import com.qkt.indicators.catalog.ATR
import com.qkt.indicators.catalog.Beta
import com.qkt.indicators.catalog.Correlation
import com.qkt.indicators.catalog.DEMA
import com.qkt.indicators.catalog.EMA
import com.qkt.indicators.catalog.EfficiencyRatio
import com.qkt.indicators.catalog.HMA
import com.qkt.indicators.catalog.Lag
import com.qkt.indicators.catalog.PercentileRank
import com.qkt.indicators.catalog.RSI
import com.qkt.indicators.catalog.RegressionSlope
import com.qkt.indicators.catalog.RunLength
import com.qkt.indicators.catalog.RunLengthWhere
import com.qkt.indicators.catalog.SMA
import com.qkt.indicators.catalog.Skew
import com.qkt.indicators.catalog.Stddev
import com.qkt.indicators.catalog.TEMA
import com.qkt.indicators.catalog.Variance
import com.qkt.indicators.catalog.VarianceRatio
import com.qkt.indicators.catalog.WMA
import com.qkt.indicators.catalog.ZScore

/**
 * Registry entries for indicators computed over one or two numeric series: moving
 * averages, RSI, dispersion and regression statistics, lag and run-length counters, and
 * the two-series CORRELATION/BETA. ATR is the one candle-fed member, kept with its
 * volatility peers.
 */
internal val seriesIndicatorSpecs: List<Pair<String, IndicatorSpec>> =
    listOf(
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
        "VARIANCE_RATIO" to
            IndicatorSpec("VARIANCE_RATIO", IndicatorInput.NUMERIC_SERIES, arity = 3) { args ->
                VarianceRatio(k = args[0].toInt(), lookback = args[1].toInt())
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
        "PERCENTILE_RANK" to
            IndicatorSpec("PERCENTILE_RANK", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                PercentileRank(period = args[0].toInt())
            },
        "SKEW" to
            IndicatorSpec("SKEW", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                Skew(period = args[0].toInt())
            },
        "ER" to
            IndicatorSpec("ER", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                EfficiencyRatio(period = args[0].toInt())
            },
        // ---- series offset ----
        "LAG" to
            IndicatorSpec("LAG", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                Lag(n = args[0].toInt())
            },
        // ---- same-direction run length (signed streak counter) ----
        "RUNLENGTH" to
            IndicatorSpec("RUNLENGTH", IndicatorInput.NUMERIC_SERIES, arity = 1) { RunLength() },
        "RUNLENGTH_WHERE" to
            IndicatorSpec("RUNLENGTH_WHERE", IndicatorInput.BOOLEAN_SERIES, arity = 1) {
                RunLengthWhere()
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
    )
