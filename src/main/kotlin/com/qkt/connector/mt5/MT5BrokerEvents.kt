package com.qkt.connector.mt5

import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The events every part of the broker raises the same way: an order rejection, and the gateway
 * going down or coming back. The gateway pair is edge-triggered on one shared flag, e.g. both
 * pollers failing in the same second publish a single `GatewayUnreachable`, and only the first
 * clean read afterwards publishes `RECONNECTED`.
 */
internal class MT5BrokerEvents(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
) {
    private val gatewayDown = AtomicBoolean(false)

    fun publishGatewayUnreachable(consecutiveFailures: Int) {
        if (!gatewayDown.compareAndSet(false, true)) return
        bus.publish(
            BrokerEvent.GatewayUnreachable(
                broker = profile.name,
                consecutiveFailures = consecutiveFailures,
                timestamp = clock.now(),
            ),
        )
    }

    fun publishGatewayRecovered(consecutiveFailures: Int) {
        if (!gatewayDown.compareAndSet(true, false)) return
        bus.publish(
            BrokerEvent.ConnectionChanged(
                broker = profile.name,
                state = BrokerEvent.ConnectionState.RECONNECTED,
                reason = "gateway-recovered",
                consecutiveFailures = consecutiveFailures,
                timestamp = clock.now(),
            ),
        )
    }

    fun reject(
        request: OrderRequest,
        reason: String,
    ): SubmitAck {
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = null,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = null,
            accepted = false,
            rejectReason = reason,
        )
    }
}
