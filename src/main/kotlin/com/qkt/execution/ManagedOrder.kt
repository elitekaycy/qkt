package com.qkt.execution

import java.math.BigDecimal

data class ManagedOrder(
    val id: String,
    val request: OrderRequest,
    val state: OrderState,
    val brokerOrderId: String? = null,
    val cumulativeFilledQuantity: BigDecimal = BigDecimal.ZERO,
    val avgFillPrice: BigDecimal? = null,
    val parentClientOrderId: String? = null,
    val childClientOrderIds: List<String> = emptyList(),
    val groupId: String? = null,
    val createdAt: Long,
    val lastUpdatedAt: Long,
)
