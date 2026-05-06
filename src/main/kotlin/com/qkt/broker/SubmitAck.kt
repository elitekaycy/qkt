package com.qkt.broker

data class SubmitAck(
    val clientOrderId: String,
    val brokerOrderId: String?,
    val accepted: Boolean,
    val rejectReason: String? = null,
)
