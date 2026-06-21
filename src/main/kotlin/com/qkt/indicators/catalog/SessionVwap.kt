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
    private var sumP2v = BigDecimal.ZERO

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = sumVol.signum() > 0

    override fun update(input: Candle) {
        val idx = Math.floorDiv(input.startTime - anchorOffsetMs, MS_PER_DAY)
        if (idx != session) {
            session = idx
            sumVol = BigDecimal.ZERO
            sumPv = BigDecimal.ZERO
            sumP2v = BigDecimal.ZERO
        }
        val volume = input.volume
        if (volume.signum() <= 0) return
        val tp =
            input.high
                .add(input.low, MathContext.DECIMAL128)
                .add(input.close, MathContext.DECIMAL128)
                .divide(THREE, MathContext.DECIMAL128)
        val pv = tp.multiply(volume, MathContext.DECIMAL128)
        sumVol = sumVol.add(volume, MathContext.DECIMAL128)
        sumPv = sumPv.add(pv, MathContext.DECIMAL128)
        sumP2v = sumP2v.add(tp.multiply(pv, MathContext.DECIMAL128), MathContext.DECIMAL128)
    }

    /** Current session VWAP and band stddev, or null until a volume-bearing candle this session. */
    fun bands(): Bands? {
        if (sumVol.signum() <= 0) return null
        val vwap = sumPv.divide(sumVol, Money.CONTEXT)
        val meanSq = sumP2v.divide(sumVol, Money.CONTEXT)
        var variance = meanSq.subtract(vwap.multiply(vwap, Money.CONTEXT), Money.CONTEXT)
        if (variance.signum() < 0) variance = BigDecimal.ZERO
        val stdev = variance.sqrt(Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
        return Bands(vwap.setScale(Money.SCALE, Money.ROUNDING), stdev)
    }

    override fun value(): BigDecimal? = bands()?.vwap
}
