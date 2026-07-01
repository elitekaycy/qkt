package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

private const val MS_PER_MINUTE = 60_000L

/**
 * The realized return since the open of the current fixed-size time bucket — a sub-bar,
 * grid-anchored move that a finer stream can read while a coarser bar is still forming.
 *
 * Bind it on a fine stream (e.g. 1m) with a coarser [bucketMinutes] (e.g. 30) and it measures
 * `close / bucket_open - 1`, where `bucket_open` is the open of the first bar of the current
 * [bucketMinutes] cell on the UTC grid. It resets at every bucket boundary. This captures the
 * intra-bar move of the forming coarse bar — invisible to plain completed-bar indicators — so a
 * rule can compare two symbols' beta-scaled intra-bar moves on the same grid, e.g.
 * `beta(gbp.close, eur.close, n) * anchored_return(gbp.candle, 30) - anchored_return(eur.candle, 30)`.
 *
 * The bucket boundary is derived purely from each candle's `startTime`, so it is deterministic and
 * reads identically in backtest and live. e.g. bucket 30m, a 1m bar closing at 103 whose bucket
 * opened at 100 reads 0.03.
 */
class AnchoredReturn(
    private val bucketMinutes: Int,
) : Indicator<Candle> {
    init {
        require(bucketMinutes > 0) { "AnchoredReturn.bucketMinutes must be > 0: $bucketMinutes" }
    }

    private val bucketMs = bucketMinutes * MS_PER_MINUTE
    private var bucket: Long = Long.MIN_VALUE
    private var anchor: BigDecimal? = null
    private var lastValue: BigDecimal? = null

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = lastValue != null

    override fun update(input: Candle) {
        val idx = Math.floorDiv(input.startTime, bucketMs)
        if (idx != bucket) {
            bucket = idx
            anchor = input.open
        }
        val a = anchor
        lastValue =
            if (a == null || a.signum() == 0) {
                null
            } else {
                input.close
                    .divide(a, Money.CONTEXT)
                    .subtract(BigDecimal.ONE, Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            }
    }

    override fun value(): BigDecimal? = lastValue
}
