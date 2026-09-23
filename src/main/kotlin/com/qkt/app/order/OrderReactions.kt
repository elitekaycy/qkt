package com.qkt.app.order

import org.slf4j.Logger

/**
 * The order manager's event-driven side, wired over [OrderWorkflows]: what happens when the
 * broker reports an order event, when a tick arrives, and when a session restarts. It depends
 * on the submission-side workflows and never the reverse.
 */
internal class OrderReactions(
    private val w: OrderWorkflows,
    s: OrderSettings,
    log: Logger,
) {
    private val store = w.store
    private val book = store.book
    private val clock = s.clock
    private val ops: OrderOps = w

    private val stopTicker =
        ManagedStopTicker(
            store.stops,
            clock,
            ops::persistAll,
            { m, level, t -> w.venueProtection.ratchet(m, level, t) },
            log,
        )
    private val venueRecovery: VenueRecovery =
        VenueRecovery(book, store.brackets, store.exposure, s.broker, s.bookedVenueTickets, clock, ops, log) { event ->
            eventHandlers.onCancelled(event)
        }
    val restorer =
        OrderRestorer(
            persistor = s.persistor,
            book = book,
            siblings = store.siblings,
            ocoGuard = w.ocoGuard,
            exposure = store.exposure,
            scaleOuts = store.scaleOuts,
            scaleOutRecovery = store.scaleOutRecovery,
            composites =
                CompositeRestore(
                    book,
                    store.children,
                    store.brackets,
                    w.bracketExits,
                    store.exposure,
                    s.broker,
                    clock,
                    ops,
                ),
            engineHeld = EngineHeldRestore(book, store.stops, store.exposure, s.broker, clock),
            venueRecovery = venueRecovery,
            snapshots = store.snapshots,
            timeExits = w.timeExits,
            broker = s.broker,
            clock = clock,
            log = log,
        )
    private val firing =
        TriggerFiring(
            book,
            store.stacks,
            store.stops,
            store.closeTickets,
            s.broker,
            clock,
            ops,
            s::closeTicket,
            s.engineHeldSubmissionBlockReason,
            log,
        )
    val tickEvaluation =
        TickEvaluation(
            book = book,
            prices = store.prices,
            stops = store.stops,
            stopTicker = stopTicker,
            timeExits = w.timeExits,
            stacks = store.stacks,
            stackExecution = w.stackExecution,
            firing = firing,
            broker = s.broker,
            clock = clock,
            ops = ops,
            requireArmedTrailTicket = s.requireArmedTrailTicket,
            closeTicket = s::closeTicket,
            log = log,
        )
    val eventHandlers: OrderEventHandlers =
        OrderEventHandlers(
            book,
            store.exposure,
            store.children,
            store.brackets,
            w.haltCancels,
            store.risk,
            w.ocoGuard,
            w.ocoSequencer,
            w.siblingCancels,
            store.scaleOuts,
            w.scaleOutTracker,
            w.scaleOutExits,
            venueRecovery,
            clock,
            ops,
            log,
        )
    val fillHandler =
        FillHandler(
            book,
            store.exposure,
            store.children,
            store.brackets,
            w.haltCancels,
            w.ocoGuard,
            w.ocoSequencer,
            w.siblingCancels,
            BracketFills(book, store.brackets, w.bracketExits, w.venueProtection, clock, ops),
            AttachedBracketCompletion(book, store.brackets, store.closeTickets, store.exposure, clock, ops),
            w.scaleOutTracker,
            w.scaleOutExits,
            ProtectiveExitGuard(book, ops, s.strategyNetQty, log),
            clock,
            ops,
            log,
        )
}
