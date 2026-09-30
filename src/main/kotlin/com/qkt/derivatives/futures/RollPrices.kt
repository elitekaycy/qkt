package com.qkt.derivatives.futures

import java.math.BigDecimal

/** The front and next contracts' reference prices at one roll transition. */
data class RollPrices(
    val fromPrice: BigDecimal,
    val toPrice: BigDecimal,
) {
    init {
        require(fromPrice.signum() > 0) { "RollPrices.fromPrice must be > 0: $fromPrice" }
        require(toPrice.signum() > 0) { "RollPrices.toPrice must be > 0: $toPrice" }
    }
}
