package com.qkt.dsl.stdlib

import com.qkt.indicators.Indicator
import com.qkt.indicators.catalog.AnchoredReturn
import com.qkt.indicators.catalog.FailedBreak
import com.qkt.indicators.catalog.IbDefended
import com.qkt.indicators.catalog.PivotPoints
import com.qkt.indicators.catalog.ReopenGap
import com.qkt.indicators.catalog.SeasonalRange
import com.qkt.indicators.catalog.SeasonalRangeStdev
import com.qkt.indicators.catalog.SessionMomentum
import com.qkt.indicators.catalog.SessionRange
import com.qkt.indicators.catalog.SessionVwap
import com.qkt.indicators.catalog.VWAP
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Registry entries for time-anchored indicators: VWAP and session VWAP, session ranges,
 * floor-trader pivots, hour-of-day seasonality, session momentum, anchored returns,
 * reopen gaps, failed-breakout latches and Initial-Balance defense memory.
 */
internal val sessionIndicatorSpecs: List<Pair<String, IndicatorSpec>> =
    listOf(
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
    )
