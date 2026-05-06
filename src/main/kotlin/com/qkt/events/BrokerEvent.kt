package com.qkt.events

import com.qkt.common.Side
import java.math.BigDecimal

sealed interface BrokerEvent : Event {
    val clientOrderId: String
    val brokerOrderId: String?

    data class OrderAccepted(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent

    data class OrderRejected(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent

    data class OrderFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent

    data class OrderPartiallyFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        val cumulativeFilled: BigDecimal,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent

    data class OrderCancelled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent
}
