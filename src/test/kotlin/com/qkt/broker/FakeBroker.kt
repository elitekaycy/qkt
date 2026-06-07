package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

class FakeBroker(
    private val bus: EventBus,
    private val clock: Clock,
    override val capabilities: Set<OrderTypeCapability>,
) : Broker {
    override val name: String = "Fake"

    val submits: MutableList<OrderRequest> = mutableListOf()
    val cancels: MutableList<String> = mutableListOf()

    var emitAcceptOnSubmit: Boolean = true

    val rejectOrderIds: MutableSet<String> = mutableSetOf()

    override fun submit(request: OrderRequest): SubmitAck {
        submits.add(request)
        if (request.id in rejectOrderIds) {
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = request.id,
                    brokerOrderId = request.id,
                    reason = "fake venue rejection",
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                accepted = false,
                rejectReason = "fake venue rejection",
            )
        }
        if (emitAcceptOnSubmit) {
            bus.publish(
                BrokerEvent.OrderAccepted(
                    clientOrderId = request.id,
                    brokerOrderId = request.id,
                    timestamp = clock.now(),
                ),
            )
        }
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    override fun cancel(orderId: String) {
        cancels.add(orderId)
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = orderId,
                brokerOrderId = orderId,
                reason = "user cancel",
                timestamp = clock.now(),
            ),
        )
    }

    data class ModifyPositionCall(
        val ticket: String,
        val sl: BigDecimal?,
        val tp: BigDecimal?,
    )

    val modifyPositions: MutableList<ModifyPositionCall> = mutableListOf()

    override fun modifyPosition(
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): SubmitAck {
        modifyPositions.add(ModifyPositionCall(ticket, sl, tp))
        return SubmitAck(ticket, ticket, accepted = true)
    }

    fun emitFill(
        request: OrderRequest,
        price: BigDecimal,
        quantity: BigDecimal = request.quantity,
    ) {
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                symbol = request.symbol,
                side = request.side,
                price = price,
                quantity = quantity,
                timestamp = clock.now(),
            ),
        )
    }
}
