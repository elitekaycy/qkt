package com.qkt.persistence

import com.qkt.execution.OrderRequest

/**
 * A live OCO leg as persisted for restart recovery — its qkt identity, its venue
 * ticket ([brokerOrderId]), and the linkage needed to rebuild cancel-on-fill.
 */
data class PersistedOcoLeg(
    val clientOrderId: String,
    val brokerOrderId: String,
    val strategyId: String,
    val request: OrderRequest,
    val siblingIds: List<String>,
)
