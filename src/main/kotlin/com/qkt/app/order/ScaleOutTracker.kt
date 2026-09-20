package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.withStrategyId

/**
 * [OrderRequest.ScaleOut] wrappers from submit until their basis resolves: an entry (the basis)
 * that, once filled, arms one engine-held take-profit per leg via [ScaleOutExits]. A basis that
 * fills only partially before it is cancelled still arms exits sized to what filled — unless the
 * user cancelled the whole wrapper.
 */
internal class ScaleOutTracker(
    private val scaleOuts: ScaleOutBook,
    private val exits: ScaleOutExits,
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    /** Starts the wrapper [req]: tracks its basis and dispatches it. */
    fun submit(req: OrderRequest.ScaleOut): SubmitAck {
        val strategyId = req.strategyId.ifBlank { req.basis.strategyId }
        val normalized = req.copy(strategyId = strategyId, basis = req.basis.withStrategyId(strategyId))
        val now = clock.now()
        ops.update(req.id) {
            it.copy(
                request = normalized,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(normalized.basis.id),
                lastUpdatedAt = now,
            )
        }
        ops.track(
            ManagedOrder(
                id = normalized.basis.id,
                request = normalized.basis,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        scaleOuts.pendingByBasis[normalized.basis.id] = normalized
        exposure.register(exposureEntryRequest(normalized.basis))
        ops.dispatch(normalized.basis)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    /** Remembers the position ticket a partial execution of a basis reported. */
    fun onBasisPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled) {
        if (e.clientOrderId !in scaleOuts.pendingByBasis) return
        e.brokerOrderId
            ?.takeIf { it.isNotBlank() }
            ?.let { scaleOuts.partialPositionTickets[e.clientOrderId] = it }
    }

    /** A basis filled completely: arm its exits sized to the whole fill. */
    fun onBasisFilled(e: BrokerEvent.OrderFilled) {
        scaleOuts.partialPositionTickets.remove(e.clientOrderId)
        scaleOuts.pendingByBasis.remove(e.clientOrderId)?.let { scaleReq ->
            exits.activate(
                scaleOut = scaleReq,
                basisQuantity = book[e.clientOrderId]?.cumulativeFilledQuantity ?: e.quantity,
                positionTicket = e.brokerOrderId?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * A basis was cancelled after [pendingScaleOut] and [partialPositionTicket] were taken off
     * the book: arm exits for whatever part filled, unless the user cancelled the wrapper itself
     * (then the partial position is theirs to manage).
     */
    fun onBasisCancelled(
        clientOrderId: String,
        pendingScaleOut: OrderRequest.ScaleOut?,
        partialPositionTicket: String?,
    ) {
        val cancelled = book[clientOrderId]
        val wrapperId = cancelled?.parentClientOrderId
        val wrapperWasExplicitlyCancelled =
            wrapperId != null &&
                (wrapperId in scaleOuts.cancellingWrappers || book[wrapperId]?.state == OrderState.CANCELLED)
        if (pendingScaleOut != null &&
            cancelled != null &&
            cancelled.cumulativeFilledQuantity.signum() > 0 &&
            !wrapperWasExplicitlyCancelled
        ) {
            exits.activate(
                scaleOut = pendingScaleOut,
                basisQuantity = cancelled.cumulativeFilledQuantity,
                positionTicket = partialPositionTicket,
            )
        }
    }

    /** Forgets a basis that will never fill (rejected, or discarded after an OCO double fill). */
    fun discardBasis(clientOrderId: String) {
        scaleOuts.pendingByBasis.remove(clientOrderId)
        scaleOuts.partialPositionTickets.remove(clientOrderId)
    }

    /** Drops [clientOrderId] from a basis that is still waiting; used when its OCO leg is compensated. */
    fun discardPendingBasis(clientOrderId: String) {
        scaleOuts.pendingByBasis.remove(clientOrderId)
    }
}
