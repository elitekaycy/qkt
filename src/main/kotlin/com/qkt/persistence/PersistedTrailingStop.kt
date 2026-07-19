package com.qkt.persistence

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * On-disk shape of an engine-managed dynamic stop so it survives a restart.
 *
 * [armed] and [hwm] carry armed-trail progress. [stepIndex] is the next stepped-stop
 * milestone, [elapsedIntervals] is the time-tightening cursor, and [stopLevel] is the
 * last monotonic level. Defaulted ratchet fields keep armed-trail journals backward
 * compatible. [request] carries static configuration and [brokerOrderId] preserves
 * the engine-held leg's accepted id.
 */
data class PersistedTrailingStop(
    val clientOrderId: String,
    val brokerOrderId: String?,
    val strategyId: String,
    val request: OrderRequest,
    val armed: Boolean,
    val hwm: BigDecimal,
    val stepIndex: Int = 0,
    val elapsedIntervals: Long = 0L,
    val stopLevel: BigDecimal? = null,
)
