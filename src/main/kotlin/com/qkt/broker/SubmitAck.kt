package com.qkt.broker

/**
 * Synchronous handshake returned by [Broker.submit].
 *
 * `accepted = true` means the broker took ownership — a fill or rejection will follow
 * on the bus. `accepted = false` is a hard rejection at submission time; [rejectReason]
 * carries the cause.
 */
data class SubmitAck(
    val clientOrderId: String,
    val brokerOrderId: String?,
    val accepted: Boolean,
    val rejectReason: String? = null,
)
