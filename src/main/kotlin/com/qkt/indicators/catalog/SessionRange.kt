package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_DAY = 1_440L

/**
 * Session-anchored range: the high and low of the most recent *completed* instance of a
 * fixed daily UTC window `[startHour:startMinute, endHour:endMinute)`, held constant as
 * price levels until the next instance completes.
 *
 * Unlike a rolling channel (`highest`/`lowest`), which slides forward bar by bar, this
 * freezes a prior session's boundaries. e.g. the overnight Asian range (00:00–07:00 UTC)
 * stays fixed through the London morning (07:00–11:30), so a strategy can fade touches of
 * the Asian high/low during London. Mid and width compose: mid = `(high+low)/2`,
 * width = `high-low`.
 *
 * The window wraps midnight when its start time-of-day is after the end (e.g. 22:00–02:00).
 * Membership is the half-open interval, so the candle at the end minute is the first one
 * outside the window and triggers the latch. Window assignment is derived purely from each
 * candle's `startTime` (no clock dependency), so the indicator is deterministic and reads
 * identically in backtest and live. Value is null until the first window completes.
 */
class SessionRange(
    private val startHour: Int,
    private val startMinute: Int,
    private val endHour: Int,
    private val endMinute: Int,
) : Indicator<Candle> {
    init {
        require(startHour in 0..23 && endHour in 0..23) { "SessionRange hours must be in 0..23" }
        require(startMinute in 0..59 && endMinute in 0..59) { "SessionRange minutes must be in 0..59" }
        require(startHour * 60 + startMinute != endHour * 60 + endMinute) {
            "SessionRange window must be non-empty (start != end)"
        }
    }

    /** The latched high and low of the most recent completed window instance. */
    data class Range(
        val high: BigDecimal,
        val low: BigDecimal,
    )

    private val start = startHour * 60 + startMinute
    private val end = endHour * 60 + endMinute

    private var activeInstance: Long? = null
    private var curHigh: BigDecimal = BigDecimal.ZERO
    private var curLow: BigDecimal = BigDecimal.ZERO
    private var latchedHigh: BigDecimal? = null
    private var latchedLow: BigDecimal? = null

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = latchedHigh != null

    override fun update(input: Candle) {
        val epochMin = Math.floorDiv(input.startTime, MS_PER_MINUTE)
        val cur = Math.floorMod(epochMin, MINUTES_PER_DAY).toInt()
        val inWindow = if (start <= end) cur in start until end else cur >= start || cur < end
        if (inWindow) {
            val instance = instanceOf(epochMin)
            if (instance != activeInstance) {
                if (activeInstance != null) latch()
                activeInstance = instance
                curHigh = input.high
                curLow = input.low
            } else {
                if (input.high > curHigh) curHigh = input.high
                if (input.low < curLow) curLow = input.low
            }
        } else if (activeInstance != null) {
            latch()
            activeInstance = null
        }
    }

    private fun instanceOf(epochMin: Long): Long {
        val dayStart = epochMin - Math.floorMod(epochMin, MINUTES_PER_DAY)
        var instanceStart = dayStart + start
        if (instanceStart > epochMin) instanceStart -= MINUTES_PER_DAY
        return instanceStart
    }

    private fun latch() {
        latchedHigh = curHigh
        latchedLow = curLow
    }

    /** The latched range, or null until the first window instance has completed. */
    fun range(): Range? {
        val h = latchedHigh ?: return null
        val l = latchedLow ?: return null
        return Range(h, l)
    }

    override fun value(): BigDecimal? = range()?.high
}
