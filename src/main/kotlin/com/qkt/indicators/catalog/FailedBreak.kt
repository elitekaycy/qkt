package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * A failed-breakout (fakeout) latch: true for a window of bars after a range boundary is pierced
 * and then reclaimed, distinguishing a trapped-breakout snap-back from a genuine break.
 *
 * The boundary is the extreme of the prior [rangeLen] bars ([high] = the highest high, else the
 * lowest low). A "pierce" is a bar trading through it (high above / low below). If a later bar
 * within [reclaimBars] closes back inside the boundary, the break failed — a fresh cohort of
 * breakout traders is now trapped — and the indicator arms: it reads 1 for [armBars] bars starting
 * at the reclaim, then 0. A pierce that keeps closing outside past [reclaimBars] is a real
 * continuation and never arms.
 *
 * Derived purely from candle highs/lows/closes, so it is deterministic and reads identically in
 * backtest and live. e.g. range high 12, a bar spikes to 15 then a later bar closes at 11 → arms.
 */
class FailedBreak(
    private val rangeLen: Int,
    private val reclaimBars: Int,
    private val armBars: Int,
    private val high: Boolean,
) : Indicator<Candle> {
    init {
        require(rangeLen > 0) { "FailedBreak.rangeLen must be > 0: $rangeLen" }
        require(reclaimBars > 0) { "FailedBreak.reclaimBars must be > 0: $reclaimBars" }
        require(armBars > 0) { "FailedBreak.armBars must be > 0: $armBars" }
    }

    private val priorExtremes: ArrayDeque<BigDecimal> = ArrayDeque(rangeLen)
    private var inPoke = false
    private var pokeBoundary: BigDecimal = BigDecimal.ZERO
    private var pokeAge = 0
    private var armCounter = 0

    override val warmupBars: Int = rangeLen + 1

    override val isReady: Boolean
        get() = priorExtremes.size >= rangeLen

    override fun update(input: Candle) {
        // A new bar consumes one bar of any active arm; a reclaim below can re-arm this same bar.
        if (armCounter > 0) armCounter--
        val boundary = boundaryOf()
        if (boundary != null) {
            if (inPoke) {
                pokeAge++
                if (reclaimed(input, pokeBoundary)) {
                    armCounter = armBars
                    inPoke = false
                } else if (pokeAge >= reclaimBars) {
                    inPoke = false
                }
            }
            if (!inPoke && armCounter == 0 && pierced(input, boundary)) {
                inPoke = true
                pokeBoundary = boundary
                pokeAge = 0
            }
        }
        priorExtremes.addLast(if (high) input.high else input.low)
        if (priorExtremes.size > rangeLen) priorExtremes.removeFirst()
    }

    private fun boundaryOf(): BigDecimal? {
        if (priorExtremes.size < rangeLen) return null
        var acc = priorExtremes.first()
        for (v in priorExtremes) acc = if (high) acc.max(v) else acc.min(v)
        return acc
    }

    private fun pierced(
        input: Candle,
        boundary: BigDecimal,
    ): Boolean = if (high) input.high > boundary else input.low < boundary

    private fun reclaimed(
        input: Candle,
        boundary: BigDecimal,
    ): Boolean = if (high) input.close < boundary else input.close > boundary

    override fun value(): BigDecimal? {
        if (!isReady) return null
        return if (armCounter > 0) BigDecimal.ONE else BigDecimal.ZERO
    }
}
