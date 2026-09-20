package com.qkt.app.order

import com.qkt.app.LegIntentPlanner
import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isCompositeShape
import com.qkt.execution.isTerminal

/**
 * Where a new order goes. Every request first gets its leg intent planned, is validated, and is
 * tracked; then [dispatch] routes it by shape and by what the venue can hold: native orders go
 * to the venue, orders the venue cannot hold rest engine-side as PENDING monitors, and
 * composites (bracket, OCO, OTO, scale-out, time exit, stack) go to their own workflow.
 * Re-submitting a live order id is idempotent.
 */
internal class OrderRouter(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val stops: ManagedStopBook,
    private val prices: ObservedPrices,
    private val children: PendingChildBook,
    private val venue: VenueSubmission,
    private val ocoSequencer: OcoSequencer,
    private val bracketSubmission: BracketSubmission,
    private val scaleOutTracker: ScaleOutTracker,
    private val timeExits: TimeExits,
    private val stackExecution: StackExecution,
    private val broker: Broker,
    private val bus: EventBus,
    private val clock: Clock,
    private val ops: OrderOps,
    private val positionMode: (symbol: String) -> PositionAccountingMode,
) {
    /** Plans [request]'s leg intent, then validates, tracks and dispatches it. */
    fun submit(request: OrderRequest): SubmitAck =
        submitPlanned(LegIntentPlanner.plan(request, positionMode(request.symbol)))

    private fun submitPlanned(request: OrderRequest): SubmitAck {
        book[request.id]?.takeIf { !it.state.isTerminal }?.let { existing ->
            return SubmitAck(
                clientOrderId = existing.id,
                brokerOrderId = existing.brokerOrderId,
                accepted = true,
            )
        }
        if (request is OrderRequest.Bracket) {
            venue.crossedProtectionRejection(request)?.let { return it }
        }
        val now = clock.now()
        ops.track(
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.CREATED,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        if (!request.isCompositeShape()) exposure.register(request)
        return dispatch(request)
    }

    /** Routes an already-tracked [request] to the venue or to an engine-held monitor. */
    fun dispatch(request: OrderRequest): SubmitAck =
        when (request) {
            is OrderRequest.Market, is OrderRequest.Limit -> venue.submitToBroker(request)

            is OrderRequest.Stop ->
                if (OrderTypeCapability.STOP in broker.capabilitiesFor(request.symbol)) {
                    venue.submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.StopLimit ->
                if (OrderTypeCapability.STOP_LIMIT in broker.capabilitiesFor(request.symbol)) {
                    venue.submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.IfTouched ->
                if (request.closesTicket == null &&
                    OrderTypeCapability.IF_TOUCHED in broker.capabilitiesFor(request.symbol)
                ) {
                    venue.submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.TrailingStop,
            is OrderRequest.TrailingStopLimit,
            is OrderRequest.ArmedTrailingStop,
            is OrderRequest.SteppedStop,
            is OrderRequest.TimeTighteningStop,
            -> holdPending(request)

            is OrderRequest.StandaloneOCO ->
                if (OrderTypeCapability.OCO in broker.capabilitiesFor(request.symbol)) {
                    venue.submitRegisteredToBroker(request)
                } else {
                    ocoSequencer.submit(request)
                }

            is OrderRequest.OTO -> submitOto(request)

            is OrderRequest.Bracket -> bracketSubmission.submit(request)

            is OrderRequest.ScaleOut -> scaleOutTracker.submit(request)

            is OrderRequest.TimeExit -> timeExits.submit(request)

            is OrderRequest.Stack -> stackExecution.submit(request)

            else -> error("Order type ${request::class.simpleName} dispatch not yet implemented (added later in 7d-b)")
        }

    private fun submitOto(req: OrderRequest.OTO): SubmitAck {
        val now = clock.now()
        val childIds = req.children.map { it.id }
        ops.update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOf(req.parent.id) + childIds,
                lastUpdatedAt = now,
            )
        }
        ops.track(
            ManagedOrder(
                id = req.parent.id,
                request = req.parent,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        for (child in req.children) {
            ops.track(
                ManagedOrder(
                    id = child.id,
                    request = child,
                    state = OrderState.CREATED,
                    parentClientOrderId = req.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                ),
            )
        }
        children.hold(req.parent.id, req.children, req)
        exposure.register(exposureEntryRequest(req.parent))
        dispatch(req.parent)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun holdPending(request: OrderRequest): SubmitAck {
        ops.update(request.id) { it.copy(state = OrderState.PENDING, lastUpdatedAt = clock.now()) }
        val trailingSeed =
            if (request is OrderRequest.TrailingStop || request is OrderRequest.TrailingStopLimit) {
                prices.priceOf(request.symbol)
            } else {
                null
            }
        stops.startTracking(request, trailingSeed)
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
}
