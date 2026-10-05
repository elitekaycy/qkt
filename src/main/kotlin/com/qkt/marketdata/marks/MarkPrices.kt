package com.qkt.marketdata.marks

import java.math.BigDecimal

/**
 * A contract's mark price (what its venue values positions and liquidates at) and index price (the spot
 * index it tracks) as the venue reported them at [timeMs]; either is null when the venue did not report it.
 */
data class MarkSample(
    val timeMs: Long,
    val mark: BigDecimal?,
    val index: BigDecimal?,
)

/**
 * Where a strategy reads contracts' mark and index prices (`<alias>.mark`, `<alias>.index`): live, the newest
 * its venue quoted; in a backtest, the stored series' newest sample before the instant asked, so a value is
 * seen only once it was known.
 */
interface MarkPrices {
    /**
     * [symbol]'s newest sample known at [atMs], read for a stream whose bars are [windowMs] long (a backtest
     * reads the series stored at that window), or null when none is known yet.
     */
    fun at(
        symbol: String,
        windowMs: Long,
        atMs: Long,
    ): MarkSample?

    /** Why [symbol]'s marks cannot be read, naming what would serve them; null when they can. */
    fun problem(symbol: String): String? = null
}
