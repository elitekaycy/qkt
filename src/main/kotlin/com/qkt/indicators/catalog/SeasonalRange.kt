package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

private const val MS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24L

/**
 * The trailing mean realized range (`high - low`) of the bars that share the current bar's
 * UTC hour-of-day — a per-hour volatility baseline that detrends the intraday "vol clock".
 *
 * Volatility is sharply seasonal: a London/NY-overlap bar is routinely wider than an Asian
 * bar simply because the session is open. A plain rolling range can't tell "the session
 * turned on" from "real news hit". This keeps a separate window per UTC hour, so dividing
 * the current bar's range by `seasonal_range` gives an excess-vol ratio that is large only
 * when a bar is wide *for its own hour* — an information shock, not the clock.
 *
 * The baseline is the mean of the last [window] *prior* occurrences of this hour, excluding
 * the current bar (so the ratio compares the bar against history, not against itself). Value
 * is null for a given hour until [window] earlier bars of that hour have been seen. Derived
 * purely from each candle's `startTime`, so it is deterministic across backtest and live.
 *
 * e.g. window 20, hour 13 ranges have averaged 0.0012 → a 13:00 bar with range 0.0030
 * reads `seasonal_range = 0.0012`, an excess ratio of 2.5.
 */
class SeasonalRange(
    private val window: Int,
) : Indicator<Candle> {
    init {
        require(window > 0) { "SeasonalRange.window must be > 0: $window" }
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
            lastValue = sum.divide(BigDecimal(window), Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
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
