package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest

/**
 * The order operations a workflow (OCO, stack, bracket, restore...) calls back into. The order
 * manager implements it; workflows never reach into its state directly, so every mutation still
 * runs through the one place that persists and indexes it.
 */
internal interface OrderOps {
    /** Plans leg intent for [request] and submits it as a new order. */
    fun submit(request: OrderRequest): SubmitAck

    /** Routes an already-tracked [request] to the venue or to an engine-held monitor. */
    fun dispatch(request: OrderRequest): SubmitAck

    /** Sends [request] straight to the venue, bypassing engine-side routing. */
    fun submitToBroker(request: OrderRequest): SubmitAck

    /** Sends [request] to the venue after moving its exposure entry onto it. */
    fun submitRegisteredToBroker(request: OrderRequest): SubmitAck

    /** Cancels [clientOrderId], cascading to a composite's children. */
    fun cancel(clientOrderId: String)

    /** Starts tracking [managed] and persists the change. */
    fun track(managed: ManagedOrder)

    /** Applies [change] to [id]; false when the order is unknown or the transition is illegal. */
    fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ): Boolean

    /** Snapshots order state for restart recovery. */
    fun persistAll()

    /**
     * Synchronously persists [strategyId]'s pending orders before venue-bound intent leaves the
     * engine, so a crash can never lose an order the venue may already hold.
     */
    fun persistSubmissionIntent(strategyId: String)

    /** Raises an operator alert that a position is not protected as intended. */
    fun reportProtectionFailure(
        strategyId: String,
        message: String,
    )
}
