package com.qkt.dsl.stdlib

import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import com.qkt.indicators.catalog.ADX
import com.qkt.indicators.catalog.ATR
import com.qkt.indicators.catalog.AnchoredReturn
import com.qkt.indicators.catalog.Beta
import com.qkt.indicators.catalog.BollingerBands
import com.qkt.indicators.catalog.CCI
import com.qkt.indicators.catalog.Correlation
import com.qkt.indicators.catalog.DEMA
import com.qkt.indicators.catalog.EMA
import com.qkt.indicators.catalog.EfficiencyRatio
import com.qkt.indicators.catalog.FailedBreak
import com.qkt.indicators.catalog.HMA
import com.qkt.indicators.catalog.IbDefended
import com.qkt.indicators.catalog.KeltnerChannels
import com.qkt.indicators.catalog.Lag
import com.qkt.indicators.catalog.MACD
import com.qkt.indicators.catalog.OBV
import com.qkt.indicators.catalog.PercentileRank
import com.qkt.indicators.catalog.PivotPoints
import com.qkt.indicators.catalog.RSI
import com.qkt.indicators.catalog.RegressionSlope
import com.qkt.indicators.catalog.ReopenGap
import com.qkt.indicators.catalog.RollingHigh
import com.qkt.indicators.catalog.RollingLow
import com.qkt.indicators.catalog.RunLength
import com.qkt.indicators.catalog.SMA
import com.qkt.indicators.catalog.SeasonalRange
import com.qkt.indicators.catalog.SeasonalRangeStdev
import com.qkt.indicators.catalog.SessionMomentum
import com.qkt.indicators.catalog.SessionRange
import com.qkt.indicators.catalog.SessionVwap
import com.qkt.indicators.catalog.Skew
import com.qkt.indicators.catalog.Stddev
import com.qkt.indicators.catalog.Stochastic
import com.qkt.indicators.catalog.TEMA
import com.qkt.indicators.catalog.VWAP
import com.qkt.indicators.catalog.Variance
import com.qkt.indicators.catalog.VarianceRatio
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
    /** True for volume-weighted indicators (VWAP, OBV) — the bound feed must supply volume. */
    val requiresVolume: Boolean = false,
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
                IndicatorSpec("OBV", IndicatorInput.CANDLE_SERIES, arity = 1, requiresVolume = true) { OBV() },
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
            // ---- Volume-weighted average price (tick-fed) ----
            "VWAP" to
                IndicatorSpec("VWAP", IndicatorInput.TICK_SERIES, arity = 2, requiresVolume = true) { args ->
                    VWAP(period = args[0].toInt())
                },
            // ---- Session-anchored VWAP + bands (candle-fed, reset each day at anchorHour UTC) ----
            "VWAP_SESSION" to
                IndicatorSpec("VWAP_SESSION", IndicatorInput.CANDLE_SERIES, arity = 2, requiresVolume = true) { args ->
                    SessionVwap(anchorHour = args[0].toInt())
                },
            "VWAP_SESSION_STDEV" to
                IndicatorSpec(
                    "VWAP_SESSION_STDEV",
                    IndicatorInput.CANDLE_SERIES,
                    arity = 2,
                    requiresVolume = true,
                ) { args ->
                    val s = SessionVwap(anchorHour = args[0].toInt())
                    object : Indicator<Candle> {
                        override fun update(input: Candle) = s.update(input)

                        override fun value(): BigDecimal? = s.bands()?.stdev

                        override val isReady: Boolean get() = s.isReady
                        override val warmupBars: Int = s.warmupBars
                    }
                },
            // ---- Session-anchored range (candle-fed; latches a prior UTC window's high/low) ----
            "SESSION_RANGE_HIGH" to
                IndicatorSpec("SESSION_RANGE_HIGH", IndicatorInput.CANDLE_SERIES, arity = 5) { args ->
                    SessionRange(
                        startHour = args[0].toInt(),
                        startMinute = args[1].toInt(),
                        endHour = args[2].toInt(),
                        endMinute = args[3].toInt(),
                    )
                },
            "SESSION_RANGE_LOW" to
                IndicatorSpec("SESSION_RANGE_LOW", IndicatorInput.CANDLE_SERIES, arity = 5) { args ->
                    val r =
                        SessionRange(
                            startHour = args[0].toInt(),
                            startMinute = args[1].toInt(),
                            endHour = args[2].toInt(),
                            endMinute = args[3].toInt(),
                        )
                    object : Indicator<Candle> {
                        override fun update(input: Candle) = r.update(input)

                        override fun value(): BigDecimal? = r.range()?.low

                        override val isReady: Boolean get() = r.isReady
                        override val warmupBars: Int = r.warmupBars
                    }
                },
            // ---- Floor-trader pivots (three outputs, candle; prior UTC-day OHLC) ----
            "PIVOT_P" to
                IndicatorSpec("PIVOT_P", IndicatorInput.CANDLE_SERIES, arity = 1) { PivotPoints() },
            "PIVOT_R1" to
                IndicatorSpec("PIVOT_R1", IndicatorInput.CANDLE_SERIES, arity = 1) {
                    val pp = PivotPoints()
                    object : Indicator<Candle> {
                        override fun update(input: Candle) = pp.update(input)

                        override fun value(): BigDecimal? = pp.levels()?.r1

                        override val isReady: Boolean get() = pp.isReady
                        override val warmupBars: Int = pp.warmupBars
                    }
                },
            "PIVOT_S1" to
                IndicatorSpec("PIVOT_S1", IndicatorInput.CANDLE_SERIES, arity = 1) {
                    val pp = PivotPoints()
                    object : Indicator<Candle> {
                        override fun update(input: Candle) = pp.update(input)

                        override fun value(): BigDecimal? = pp.levels()?.s1

                        override val isReady: Boolean get() = pp.isReady
                        override val warmupBars: Int = pp.warmupBars
                    }
                },
            // ---- Hour-of-day volatility seasonality (candle; mean/stdev range per UTC hour) ----
            "SEASONAL_RANGE" to
                IndicatorSpec("SEASONAL_RANGE", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    SeasonalRange(window = args[0].toInt())
                },
            "SEASONAL_RANGE_STDEV" to
                IndicatorSpec("SEASONAL_RANGE_STDEV", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    SeasonalRangeStdev(window = args[0].toInt())
                },
            // ---- Session-restricted momentum (candle; in-window drift over nDays) ----
            "SESSION_MOMENTUM" to
                IndicatorSpec("SESSION_MOMENTUM", IndicatorInput.CANDLE_SERIES, arity = 4) { args ->
                    SessionMomentum(
                        startHour = args[0].toInt(),
                        endHour = args[1].toInt(),
                        nDays = args[2].toInt(),
                    )
                },
            // ---- Return since the current time-bucket open (candle; resets each bucket) ----
            "ANCHORED_RETURN" to
                IndicatorSpec("ANCHORED_RETURN", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    AnchoredReturn(bucketMinutes = args[0].toInt())
                },
            // ---- Session-boundary reopen gap (candle; size / origin / fill-fraction) ----
            "REOPEN_GAP" to
                IndicatorSpec("REOPEN_GAP", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    ReopenGap(minGapHours = args[0].toInt())
                },
            "REOPEN_GAP_ORIGIN" to
                IndicatorSpec("REOPEN_GAP_ORIGIN", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    val g = ReopenGap(minGapHours = args[0].toInt())
                    object : Indicator<Candle> {
                        override fun update(input: Candle) = g.update(input)

                        override fun value(): BigDecimal? = g.origin()

                        override val isReady: Boolean get() = g.isReady
                        override val warmupBars: Int = g.warmupBars
                    }
                },
            "GAP_FILL_FRACTION" to
                IndicatorSpec("GAP_FILL_FRACTION", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    val g = ReopenGap(minGapHours = args[0].toInt())
                    object : Indicator<Candle> {
                        override fun update(input: Candle) = g.update(input)

                        override fun value(): BigDecimal? = g.fillFraction()

                        override val isReady: Boolean get() = g.isReady
                        override val warmupBars: Int = g.warmupBars
                    }
                },
            // ---- Failed-breakout (fakeout) latch (candle; pierce then reclaim, armed M bars) ----
            "FAILED_BREAK_HIGH" to
                IndicatorSpec("FAILED_BREAK_HIGH", IndicatorInput.CANDLE_SERIES, arity = 4) { args ->
                    FailedBreak(
                        rangeLen = args[0].toInt(),
                        reclaimBars = args[1].toInt(),
                        armBars = args[2].toInt(),
                        high = true,
                    )
                },
            "FAILED_BREAK_LOW" to
                IndicatorSpec("FAILED_BREAK_LOW", IndicatorInput.CANDLE_SERIES, arity = 4) { args ->
                    FailedBreak(
                        rangeLen = args[0].toInt(),
                        reclaimBars = args[1].toInt(),
                        armBars = args[2].toInt(),
                        high = false,
                    )
                },
            // ---- Initial-Balance prior-defense memory (candle; tested-and-held earlier this session) ----
            "IB_DEFENDED_HIGH" to
                IndicatorSpec("IB_DEFENDED_HIGH", IndicatorInput.CANDLE_SERIES, arity = 3) { args ->
                    IbDefended(sessionStartHour = args[0].toInt(), ibMinutes = args[1].toInt(), high = true)
                },
            "IB_DEFENDED_LOW" to
                IndicatorSpec("IB_DEFENDED_LOW", IndicatorInput.CANDLE_SERIES, arity = 3) { args ->
                    IbDefended(sessionStartHour = args[0].toInt(), ibMinutes = args[1].toInt(), high = false)
                },
            // ---- Donchian rolling extremes ----
            // Rules evaluate after the closing bar has already been pushed into every
            // indicator (update-then-fire), so a raw rolling extreme would include the
            // current bar and `close > highest(close, N)` could never be true. The
            // one-bar lag makes HIGHEST/LOWEST cover the N bars BEFORE the current one,
            // matching the documented breakout semantics (reference/dsl/indicators.md).
            "HIGHEST" to
                IndicatorSpec("HIGHEST", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    PriorBars(RollingHigh(period = args[0].toInt()))
                },
            "LOWEST" to
                IndicatorSpec("LOWEST", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    PriorBars(RollingLow(period = args[0].toInt()))
                },
        )

    /**
     * Reports [inner]'s value as it stood BEFORE the most recent update — i.e. over
     * the window of bars prior to the current one. e.g. a RollingHigh(3) fed
     * 1, 2, 3, then 9: the raw indicator says max(2, 3, 9) = 9, so `close > highest`
     * can never fire; through this lag it says max(1, 2, 3) = 3, and 9 > 3 fires.
     */
    private class PriorBars(
        private val inner: Indicator<BigDecimal>,
    ) : Indicator<BigDecimal> {
        private var prev: BigDecimal? = null
        private var prevReady = false

        override fun update(input: BigDecimal) {
            prev = inner.value()
            prevReady = inner.isReady
            inner.update(input)
        }

        override fun value(): BigDecimal? = prev

        override val isReady: Boolean get() = prevReady

        override val warmupBars: Int = inner.warmupBars + 1
    }

    /** Every registered indicator name (uppercase), for editor tooling (completion, hover). */
    fun names(): Set<String> = table.keys

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
