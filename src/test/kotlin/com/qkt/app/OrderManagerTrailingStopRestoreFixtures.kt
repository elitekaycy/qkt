package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.execution.ManagedOrder

object OrderManagerTrailingStopRestoreFixtures {
    class RecoveryRecordingBroker(
        delegate: Broker,
    ) : Broker by delegate {
        val recovered = mutableListOf<ManagedOrder>()

        override fun recoverPendingOrders(
            orders: List<ManagedOrder>,
            bookedTickets: Set<String>,
        ): Set<String> {
            recovered += orders
            return orders.mapTo(LinkedHashSet()) { it.id }
        }
    }
}
