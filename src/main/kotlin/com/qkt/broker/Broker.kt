package com.qkt.broker

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

interface Broker {
    val name: String
    val capabilities: Set<OrderTypeCapability>

    fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> = capabilities

    fun supports(symbol: String): Boolean = true

    fun submit(request: OrderRequest): SubmitAck

    fun cancel(orderId: String)

    fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck = throw UnsupportedOperationException("$name does not support modify")
}

data class OrderModification(
    val newQuantity: BigDecimal? = null,
    val newLimitPrice: BigDecimal? = null,
    val newStopPrice: BigDecimal? = null,
)
