package com.qkt.instrument

import java.math.BigDecimal

/**
 * One published funding rate of a perpetual, the same shape whatever the venue: a unit long held through
 * [timeMs] pays `rate × price` per unit of the underlying (times the contract size); a negative [rate]
 * pays the short. [price] is the price the venue applied (its mark or index), null when it published
 * none, and the holder's last price is used then.
 */
data class FundingRate(
    val timeMs: Long,
    val rate: BigDecimal,
    val price: BigDecimal?,
) {
    init {
        require(price == null || price.signum() > 0) { "FundingRate.price must be positive: $price" }
    }
}

/** Where a perpetual's published funding rates come from: a gateway account, or a venue's public API. */
fun interface FundingRateSource {
    /** The funding rates of perpetual [qktSymbol] published from [fromMs] to [toMs], oldest first. */
    fun rates(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<FundingRate>
}
