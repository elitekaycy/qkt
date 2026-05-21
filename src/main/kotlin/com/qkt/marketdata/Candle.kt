package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * OHLC candle aggregated over a closed time window.
 *
 * Produced by the candle aggregator on its `EVERY` boundary and published as
 * [com.qkt.events.CandleEvent]. `endTime` is exclusive — the window covers
 * `[startTime, endTime)`. `bid`/`ask` are the quote from the last tick in the
 * window; they are `null` when the feed carries no bid/ask. `mid` and `spread`
 * are derived, computed on access, and `null` when either side is absent.
 */
data class Candle(
    val symbol: String,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val startTime: Long,
    val endTime: Long,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
) {
    /** Midpoint of bid+ask, or `null` if either side isn't populated. */
    val mid: BigDecimal?
        get() =
            if (bid != null && ask != null) {
                bid
                    .add(ask, Money.CONTEXT)
                    .divide(BigDecimal(2), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            } else {
                null
            }

    /** ask − bid, or `null` if either side isn't populated. */
    val spread: BigDecimal?
        get() =
            if (bid != null && ask != null) {
                ask.subtract(bid, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            } else {
                null
            }
}
