package com.qkt.strategy

import java.math.BigDecimal

sealed class Signal {
    data class Buy(
        val symbol: String,
        val size: BigDecimal,
    ) : Signal()

    data class Sell(
        val symbol: String,
        val size: BigDecimal,
    ) : Signal()
}
