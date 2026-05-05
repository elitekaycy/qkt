package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.RefreshTrigger
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import java.time.Instant
import java.time.ZoneOffset

open class RangeAggregateIndicator<T : Any>(
    private val symbol: String,
    private val window: TimeWindow,
    private val rangeSpec: () -> TimeRange,
    private val reduce: (Sequence<Candle>) -> T?,
    private val source: MarketSource,
    private val clock: Clock,
    private val refreshOn: RefreshTrigger,
) {
    private var cached: T? = null
    private var hasComputed: Boolean = false
    private var lastRefreshKey: Long = 0L
    private var ticksSinceRefresh: Int = 0

    fun update(tick: Tick) {
        if (tick.symbol != symbol) return
        val key = currentRefreshKey()
        if (!hasComputed || key != lastRefreshKey) {
            cached = reduce(source.bars(symbol, window, rangeSpec()))
            lastRefreshKey = key
            hasComputed = true
        }
    }

    fun value(): T? = cached

    val isReady: Boolean get() = cached != null

    private fun currentRefreshKey(): Long {
        val now = Instant.ofEpochMilli(clock.now())
        return when (val r = refreshOn) {
            is RefreshTrigger.Once -> 0L
            is RefreshTrigger.EveryNTicks -> {
                ticksSinceRefresh++
                if (ticksSinceRefresh >= r.n) {
                    ticksSinceRefresh = 0
                    lastRefreshKey + 1
                } else {
                    lastRefreshKey
                }
            }
            is RefreshTrigger.OnAnchorRollover -> r.calendar.anchorEpochFor(r.anchor, now)
            RefreshTrigger.OnSessionRollover -> now.epochSecond / 86_400L
            is RefreshTrigger.OnTimeOfDay -> {
                val zdt = now.atZone(ZoneOffset.UTC)
                val today = zdt.toLocalDate().atTime(r.time).toInstant(ZoneOffset.UTC)
                if (now >= today) today.toEpochMilli() else today.minusSeconds(86_400).toEpochMilli()
            }
        }
    }
}
