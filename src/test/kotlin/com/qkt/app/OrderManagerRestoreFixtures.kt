package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.persistence.PersistedOcoLeg
import java.math.BigDecimal

object OrderManagerRestoreFixtures {
    fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    class RecordingBroker(
        delegate: Broker,
        private val onRecover: (List<ManagedOrder>) -> Unit = {},
    ) : Broker by delegate {
        val recovered = mutableListOf<ManagedOrder>()

        /** Ids the venue reports no counterpart for (nothing pending, no position, no ticket). */
        val unaccounted = mutableSetOf<String>()

        override fun recoverPendingOrders(
            orders: List<ManagedOrder>,
            bookedTickets: Set<String>,
        ): Set<String> {
            recovered += orders
            onRecover(orders)
            return orders.filterNot { it.id in unaccounted }.mapTo(LinkedHashSet()) { it.id }
        }
    }

    fun ocoLeg(
        id: String,
        side: Side,
        ticket: String,
        siblings: List<String>,
    ) = PersistedOcoLeg(
        clientOrderId = id,
        brokerOrderId = ticket,
        strategyId = "alpha",
        request =
            OrderRequest.Stop(
                id = id,
                symbol = "XAUUSD",
                side = side,
                quantity = BigDecimal("1"),
                stopPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        siblingIds = siblings,
    )
}
