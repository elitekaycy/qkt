package com.qkt.execution

import java.math.BigDecimal

/**
 * The order manager's in-memory record of a submitted [OrderRequest].
 *
 * Holds the live [OrderState], cumulative fill state, and parent/child linkage for
 * composite shapes (Bracket, OCO, OTO, etc). The order manager mutates this through
 * `copy(...)` on every broker event; consumers see only the latest snapshot.
 */
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
