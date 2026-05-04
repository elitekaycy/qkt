package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal

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

    val spread: BigDecimal?
        get() =
            if (bid != null && ask != null) {
                ask.subtract(bid, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            } else {
                null
            }
}
