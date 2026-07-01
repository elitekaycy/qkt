package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

private const val MS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24L

/**
 * The trailing sample standard deviation of realized range (`high - low`) across the bars that
 * share the current bar's UTC hour-of-day — the dispersion companion to [SeasonalRange]'s mean.
 *
 * [SeasonalRange] gives the per-hour *expected* range; this gives how much that range varies
 * within the hour. Together they let a strategy z-score a bar against its own hour:
 * `(range - seasonal_range) / seasonal_range_stdev` is large only when a bar is wide relative to
 * the normal spread of its hour — an information shock, not the session's routine vol clock.
 *
 * The baseline is the sample stdev (n-1 divisor, matching [Stddev]) of the last [window] *prior*
 * occurrences of this hour, excluding the current bar. Value is null for a given hour until
 * [window] earlier bars of that hour have been seen. Derived purely from each candle's
 * `startTime`, so it is deterministic across backtest and live.
 *
 * e.g. window 3, hour 13 ranges 2, 4, 6 → mean 4, sample variance 8/2 = 4, stdev = 2.
 */
class SeasonalRangeStdev(
    private val window: Int,
) : Indicator<Candle> {
    init {
        require(window > 1) { "SeasonalRangeStdev.window must be > 1 (sample stddev needs n-1 divisor): $window" }
    }

    private val buckets: Array<ArrayDeque<BigDecimal>> = Array(24) { ArrayDeque(window) }
    private var lastValue: BigDecimal? = null
    private var lastReady = false

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = lastReady

    override fun update(input: Candle) {
        val hour = Math.floorMod(Math.floorDiv(input.startTime, MS_PER_HOUR), HOURS_PER_DAY).toInt()
        val bucket = buckets[hour]
        if (bucket.size >= window) {
            var sum = BigDecimal.ZERO
            for (r in bucket) sum = sum.add(r, Money.CONTEXT)
            val mean = sum.divide(BigDecimal(window), Money.CONTEXT)
            var ssd = BigDecimal.ZERO
            for (r in bucket) {
                val d = r.subtract(mean, Money.CONTEXT)
                ssd = ssd.add(d.multiply(d, Money.CONTEXT), Money.CONTEXT)
            }
            val variance = ssd.divide(BigDecimal(window - 1), Money.CONTEXT)
            lastValue = variance.sqrt(Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            lastReady = true
        } else {
            lastValue = null
            lastReady = false
        }
        bucket.addLast(input.high.subtract(input.low, Money.CONTEXT))
        if (bucket.size > window) bucket.removeFirst()
    }

    override fun value(): BigDecimal? = lastValue
}
