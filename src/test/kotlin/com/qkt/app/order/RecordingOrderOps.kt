package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest

/** [OrderOps] that records submits and operator alerts; every other operation is unexpected. */
internal class RecordingOrderOps(
    var ack: (OrderRequest) -> SubmitAck = { SubmitAck(it.id, it.id, accepted = true) },
) : OrderOps {
    val submitted = mutableListOf<OrderRequest>()
    val alerts = mutableListOf<String>()

    override fun submit(request: OrderRequest): SubmitAck {
        submitted += request
        return ack(request)
    }

    override fun reportProtectionFailure(
        strategyId: String,
        message: String,
    ) {
        alerts += "$strategyId: $message"
    }

    override fun dispatch(request: OrderRequest) = error("unused")

    override fun submitToBroker(request: OrderRequest) = error("unused")

    override fun submitRegisteredToBroker(request: OrderRequest) = error("unused")

    override fun cancel(clientOrderId: String) = error("unused")

    override fun track(managed: ManagedOrder) = error("unused")

    override fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ) = error("unused")

    override fun rejectEngineHeld(
        request: OrderRequest,
        reason: String,
    ) = error("unused")

    override fun persistAll() = error("unused")

    override fun persistSubmissionIntent(strategyId: String) = error("unused")
}
