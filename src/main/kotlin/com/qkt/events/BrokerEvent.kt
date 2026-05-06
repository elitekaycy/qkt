package com.qkt.events

import com.qkt.common.Side
import java.math.BigDecimal

sealed interface BrokerEvent : Event {
    sealed interface OrderEvent : BrokerEvent {
        val clientOrderId: String
        val brokerOrderId: String?
        val strategyId: String
    }

    data class OrderAccepted(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class OrderRejected(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class OrderFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class OrderPartiallyFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        val cumulativeFilled: BigDecimal,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class OrderCancelled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class BalancesUpdated(
        val balances: Map<String, BigDecimal>,
        val source: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent

    data class PositionReconciled(
        val symbol: String,
        val oldQty: BigDecimal?,
        val newQty: BigDecimal,
        val oldAvgPx: BigDecimal?,
        val newAvgPx: BigDecimal,
        val source: String,
        val reason: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent
}
