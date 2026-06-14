package com.qkt.persistence

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * On-disk shape of an engine-managed armed trailing stop so it survives a restart.
 *
 * The engine runs the trail in memory: [armed] is whether the stop has crossed its arming
 * threshold yet, and [hwm] is the best price reached so far — the high-water mark the stop
 * trails behind. Both are lost on reboot unless persisted; without them a winner that had
 * already armed comes back stop-less until the trail re-arms from the entry. [request] carries
 * the static config (entry price, trail distance, MFE threshold) and [brokerOrderId] the venue
 * ticket the close-by-ticket exit targets.
 */
data class PersistedTrailingStop(
    val clientOrderId: String,
    val brokerOrderId: String?,
    val strategyId: String,
    val request: OrderRequest,
    val armed: Boolean,
    val hwm: BigDecimal,
)
