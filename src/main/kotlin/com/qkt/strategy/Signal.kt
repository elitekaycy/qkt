package com.qkt.strategy

import com.qkt.execution.OrderRequest
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

    data class Submit(
        val request: OrderRequest,
    ) : Signal()
}
