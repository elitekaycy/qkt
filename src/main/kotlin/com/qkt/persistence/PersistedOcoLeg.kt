package com.qkt.persistence

import com.qkt.execution.OrderRequest

/**
 * An OCO leg as persisted for restart recovery — its qkt identity, its venue
 * ticket ([brokerOrderId]), and the linkage needed to rebuild cancel-on-fill. A live leg has
 * [executed] false; a leg that filled while another leg of its OCO is still live is kept with
 * [executed] true (its [brokerOrderId] is then the position ticket), so a restart can still cancel
 * that other leg and close it if it fills too.
 */
data class PersistedOcoLeg(
    val clientOrderId: String,
    val brokerOrderId: String,
    val strategyId: String,
    val request: OrderRequest,
    val siblingIds: List<String>,
    val executed: Boolean = false,
)
