package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * A single market data point — the unit that drives everything in qkt.
 *
 * `price` is the canonical trade-price field; brokers and strategies should read it
 * by default. `bid`/`ask` are optional — populate when the data source exposes them.
 * `mid` and `spread` are derived properties; they return `null` when bid/ask aren't set.
 */
data class Tick(
    val symbol: String,
    val price: BigDecimal,
    val timestamp: Long,
    val volume: BigDecimal? = null,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val bidVolume: BigDecimal? = null,
    val askVolume: BigDecimal? = null,
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
