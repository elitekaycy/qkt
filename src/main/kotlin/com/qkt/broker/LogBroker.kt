package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import org.slf4j.LoggerFactory

class LogBroker(
    private val bus: EventBus,
    private val clock: Clock,
) : Broker {
    private val log = LoggerFactory.getLogger(LogBroker::class.java)

    private val strategyByOrderId: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    override val name: String = "Log"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
        )

    override fun submit(request: OrderRequest): SubmitAck {
        log.info("ORDER: {}", request)
        strategyByOrderId[request.id] = request.strategyId
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    override fun cancel(orderId: String) {
        log.info("CANCEL: {}", orderId)
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = orderId,
                brokerOrderId = orderId,
                reason = "user cancel",
                strategyId = strategyByOrderId.remove(orderId) ?: "",
                timestamp = clock.now(),
            ),
        )
    }
}
