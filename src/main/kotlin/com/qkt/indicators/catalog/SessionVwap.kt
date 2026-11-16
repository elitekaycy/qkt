package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.math.MathContext

private const val MS_PER_DAY = 86_400_000L
private const val MS_PER_HOUR = 3_600_000L
private val THREE = BigDecimal(3)

/**
 * Session-anchored Volume-Weighted Average Price and its volume-weighted standard
 * deviation, both reset each day at [anchorHour] UTC.
 *
 * VWAP is the price large execution desks benchmark against ("did we beat VWAP?"), so
 * intraday excursions from it tend to be leaned against and revert. Anchoring resets the
 * running average at a fixed hour — `anchorHour = 0` is the classic session-open VWAP;
 * `anchorHour = 12` anchors at the London/NY overlap. The standard deviation supplies
 * symmetric bands: a strategy fades touches of `vwap ± k*stdev` back toward `vwap`.
 *
 * Typical price per candle is `(high + low + close) / 3`. The reset boundary is derived
 * purely from each candle's own `startTime` (no clock dependency), so the indicator is
 * deterministic and reads identically in backtest and live. Volume-less candles
 * (volume 0) carry no weight, matching [VWAP].
 *
 * The variance is the volume-weighted population variance of typical price around the
 * VWAP, accumulated with West's incremental update (`m2 += v * (tp - oldMean) * (tp - newMean)`)
 * rather than `E[tp²] - E[tp]²`: the one-pass form cancels catastrophically at large prices
 * (a spurious ~0.001 stdev on a session's first bar at BTC prices), the incremental form is
 * exactly zero there and stays accurate throughout the session.
 *
 * e.g. two equal-volume candles at typical prices 10 and 20 → vwap 15, stdev 5.
 */
class SessionVwap(
    private val anchorHour: Int,
) : Indicator<Candle> {
    init {
        require(anchorHour in 0..23) { "SessionVwap.anchorHour must be in 0..23: $anchorHour" }
    }

    /** The session VWAP and the volume-weighted standard deviation of price around it. */
    data class Bands(
        val vwap: BigDecimal,
        val stdev: BigDecimal,
    )

    private val anchorOffsetMs = anchorHour * MS_PER_HOUR
    private var session: Long = Long.MIN_VALUE
    private var sumVol = BigDecimal.ZERO
    private var sumPv = BigDecimal.ZERO

    /** Running volume-weighted mean of typical price, at full precision (the rounded VWAP is derived in [bands]). */
    private var mean = BigDecimal.ZERO

    /** Running sum of `v * (tp - mean)²`, so `m2 / sumVol` is the weighted population variance. */
    private var m2 = BigDecimal.ZERO
    private var completeSession = false

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = sumVol.signum() > 0

    override fun update(input: Candle) {
        val idx = Math.floorDiv(input.startTime - anchorOffsetMs, MS_PER_DAY)
        if (idx != session) {
            session = idx
            completeSession = Math.floorMod(input.startTime - anchorOffsetMs, MS_PER_DAY) == 0L
            sumVol = BigDecimal.ZERO
            sumPv = BigDecimal.ZERO
            mean = BigDecimal.ZERO
            m2 = BigDecimal.ZERO
        }
        if (!completeSession) return
        val volume = input.volume
        if (volume.signum() <= 0) return
        val tp =
            input.high
                .add(input.low, MathContext.DECIMAL128)
                .add(input.close, MathContext.DECIMAL128)
                .divide(THREE, MathContext.DECIMAL128)
        val first = sumVol.signum() == 0
        sumVol = sumVol.add(volume, MathContext.DECIMAL128)
        sumPv = sumPv.add(tp.multiply(volume, MathContext.DECIMAL128), MathContext.DECIMAL128)
        if (first) {
            mean = tp
            return
        }
        val delta = tp.subtract(mean, MathContext.DECIMAL128)
        val newMean = sumPv.divide(sumVol, MathContext.DECIMAL128)
        val spread = delta.multiply(tp.subtract(newMean, MathContext.DECIMAL128), MathContext.DECIMAL128)
        m2 = m2.add(volume.multiply(spread, MathContext.DECIMAL128), MathContext.DECIMAL128)
        mean = newMean
    }

    /** Current session VWAP and band stddev, or null until a volume-bearing candle this session. */
    fun bands(): Bands? {
        if (sumVol.signum() <= 0) return null
        val vwap = sumPv.divide(sumVol, Money.CONTEXT)
        // m2 is a sum of non-negative terms; max guards the last-digit rounding of a near-constant session.
        val variance = m2.max(BigDecimal.ZERO).divide(sumVol, MathContext.DECIMAL128)
        val stdev = variance.sqrt(Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
        return Bands(vwap.setScale(Money.SCALE, Money.ROUNDING), stdev)
    }

    override fun value(): BigDecimal? = bands()?.vwap
}
