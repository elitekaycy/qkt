package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow

/**
 * A bar source that remembers what it read and can be told to read again. Only sources that front a
 * live venue are refreshable: a venue's history can lag its own ticks (a busy MT5 terminal served
 * 1m bars ending 13:02 at 13:07), and a cached read would repeat that lag for the cache's lifetime.
 */
interface RefreshableBars {
    /** Drop every remembered read of [symbol] at [window], so the next one goes to the venue. */
    fun forgetBars(
        symbol: String,
        window: TimeWindow,
    )
}
