package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import org.slf4j.Logger

/**
 * The order manager's submission-side workflows (routing, OCO, brackets, stacks, scale-outs,
 * cancellation), wired over one [OrderStore]. [OrderReactions] builds the event-driven side on top. None of these constructors subscribes to
 * the bus; the order manager does that, in its documented order. Collaborators reach order operations through this class as
 * [OrderOps]: each call forwards to the workflow that owns it, which is what lets a workflow
 * constructed early call one constructed later.
 */
internal class OrderWorkflows(
    s: OrderSettings,
    log: Logger,
) : OrderOps {
    val store: OrderStore =
        OrderStore(s, log, isReferenced = { id ->
            reclamation.isReferenced(id)
        }, reclaim = { id -> reclamation.reclaim(id) })
    private val book = store.book
    private val clock = s.clock

    private val ops: OrderOps = this

    val haltCancels =
        HaltCancellations(book, s.broker, clock) { sid, message -> store.reportProtectionFailure(sid, message) }
    val ocoGuard = OcoExecutionGuard(book, store.siblings, clock, ops)
    val ocoSequencer = OcoSequencer(book, store.exposure, store.siblings, ocoGuard, clock, ops)
    val siblingCancels = SiblingCancellation(book, store.siblings, ocoSequencer, ops)
    val venueProtection: VenuePositionProtection =
        VenuePositionProtection(
            broker = s.broker,
            bus = s.bus,
            ops = ops,
            closeTicket = s::closeTicket,
            armStackFallbackStop = {
                stackId,
                layer,
                fill,
                ticket,
                ->
                stackExits.attachStopLoss(stackId, layer, fill, ticket)
            },
            armBracketFallbackStop = { stop, ticket -> store.armEngineHeldStop(stop, ticket) },
        )
    val scaleOutExits =
        ScaleOutExits(store.scaleOuts, book, store.exposure, s.broker, s.bus, clock, ops, s.requireArmedTrailTicket)
    val scaleOutTracker = ScaleOutTracker(store.scaleOuts, scaleOutExits, book, store.exposure, clock, ops)
    val bracketExits = BracketExits(store.prices, clock)
    val stackExits: StackLayerExits =
        StackLayerExits(store.stacks, book, store.closeTickets, venueProtection, clock, ops, s.closeTicketFor)
    val stackExecution =
        StackExecution(
            store.stacks,
            StackLayerOrders(clock, s.positionMode),
            stackExits,
            book,
            store.exposure,
            store.siblings,
            s.broker,
            clock,
            ops,
            s.engineHeldSubmissionBlockReason,
            log,
        )
    val timeExits = TimeExits(book, store.exposure, clock, ops)
    val cancellation: OrderCancellation =
        OrderCancellation(
            book,
            store.exposure,
            store.stacks,
            store.scaleOuts,
            scaleOutExits,
            haltCancels,
            store.closeTickets,
            s.broker,
            clock,
            ops,
            s.isRiskReducingForHalt,
        )
    private val venue: VenueSubmission =
        VenueSubmission(book, store.exposure, s.broker, s.bus, s.priceProvider, clock, ops, log)
    val router: OrderRouter =
        OrderRouter(
            book = book,
            exposure = store.exposure,
            stops = store.stops,
            prices = store.prices,
            children = store.children,
            venue = venue,
            ocoSequencer = ocoSequencer,
            bracketSubmission =
                BracketSubmission(
                    s.broker,
                    s.priceProvider,
                    bracketExits,
                    store.risk,
                    store.brackets,
                    store.children,
                    store.exposure,
                    book,
                    clock,
                    ops,
                ),
            scaleOutTracker = scaleOutTracker,
            timeExits = timeExits,
            stackExecution = stackExecution,
            broker = s.broker,
            bus = s.bus,
            clock = clock,
            ops = ops,
            positionMode = s.positionMode,
        )
    private val reclamation = OrderReclamation(store, ocoGuard, siblingCancels, timeExits)

    override fun submit(request: OrderRequest): SubmitAck = router.submit(request)

    override fun dispatch(request: OrderRequest): SubmitAck = router.dispatch(request)

    override fun cancel(clientOrderId: String) = cancellation.cancel(clientOrderId)

    override fun track(managed: ManagedOrder) = store.track(managed)

    override fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ): Boolean = store.update(id, change)

    override fun submitToBroker(request: OrderRequest): SubmitAck = venue.submitToBroker(request)

    override fun submitRegisteredToBroker(request: OrderRequest): SubmitAck = venue.submitRegisteredToBroker(request)

    override fun rejectEngineHeld(
        request: OrderRequest,
        reason: String,
    ) = venue.rejectEngineHeld(request, reason)

    override fun persistAll() = store.snapshots.persistAll()

    override fun persistSubmissionIntent(strategyId: String) = store.snapshots.persistSubmissionIntent(strategyId)

    override fun reportProtectionFailure(
        strategyId: String,
        message: String,
    ) = store.reportProtectionFailure(strategyId, message)
}
