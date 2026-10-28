package com.qkt.broker

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/** [this] order at [quantity]: a simulated venue's volume after rounding down to the instrument's step. */
internal fun OrderRequest.withVolume(quantity: BigDecimal): OrderRequest =
    when (this) {
        is OrderRequest.Market -> copy(quantity = quantity)
        is OrderRequest.Limit -> copy(quantity = quantity)
        is OrderRequest.Stop -> copy(quantity = quantity)
        is OrderRequest.StopLimit -> copy(quantity = quantity)
        is OrderRequest.IfTouched -> copy(quantity = quantity)
        else -> error("a simulated venue cannot size a ${this::class.simpleName}")
    }
