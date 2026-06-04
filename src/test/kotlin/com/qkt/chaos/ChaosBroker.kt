package com.qkt.chaos

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.execution.OrderRequest
import com.qkt.positions.Position

/**
 * A [Broker] decorator that injects faults from a [ChaosFaultModel] for chaos tests, otherwise
 * delegating. e.g. `ChaosBroker(real, ChaosFaultModel(submitFault = REJECT))` makes every submit
 * come back un-accepted without reaching `real`.
 */
class ChaosBroker(
    private val delegate: Broker,
    private val faults: ChaosFaultModel,
) : Broker {
    override val name: String = "Chaos(${delegate.name})"
    override val capabilities: Set<OrderTypeCapability> = delegate.capabilities

    override fun submit(request: OrderRequest): SubmitAck =
        when (faults.submitFault) {
            SubmitFault.NONE -> delegate.submit(request)
            SubmitFault.REJECT ->
                SubmitAck(request.id, null, accepted = false, rejectReason = "chaos: injected rejection")
            SubmitFault.THROW -> throw RuntimeException("chaos: injected submit exception")
        }

    override fun cancel(orderId: String) = delegate.cancel(orderId)

    override fun getOpenPositions(): Map<String, List<Position>> =
        if (faults.stalePositions) emptyMap() else delegate.getOpenPositions()

    override fun shutdown() = delegate.shutdown()
}
