package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState

/** [OrderOps] over a real [OrderBook]: cancels and updates land in the book; sends are recorded. */
internal class BookOrderOps(
    private val book: OrderBook,
) : OrderOps {
    val cancelled = mutableListOf<String>()
    val dispatched = mutableListOf<OrderRequest>()

    override fun cancel(clientOrderId: String) {
        cancelled += clientOrderId
        book[clientOrderId]?.let { book.put(it.copy(state = OrderState.CANCELLED)) }
    }

    override fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ): Boolean {
        val managed = book[id] ?: return false
        book.put(change(managed))
        return true
    }

    override fun track(managed: ManagedOrder) = book.put(managed)

    override fun dispatch(request: OrderRequest): SubmitAck {
        dispatched += request
        return SubmitAck(request.id, request.id, accepted = true)
    }

    override fun submit(request: OrderRequest) = dispatch(request)

    override fun submitToBroker(request: OrderRequest) = dispatch(request)

    override fun submitRegisteredToBroker(request: OrderRequest) = dispatch(request)

    override fun rejectEngineHeld(
        request: OrderRequest,
        reason: String,
    ) {
        update(request.id) { it.copy(state = OrderState.REJECTED) }
    }

    override fun persistAll() {}

    override fun persistSubmissionIntent(strategyId: String) {}

    override fun reportProtectionFailure(
        strategyId: String,
        message: String,
    ) {}
}
