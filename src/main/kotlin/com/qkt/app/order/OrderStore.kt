package com.qkt.app.order

import com.qkt.app.StackTracker
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal
import org.slf4j.Logger

/**
 * Every piece of order state the workflows share, plus the two primitives that change an order
 * record: [track] a new order and [update] an existing one. Both persist the change, and
 * [update] refuses to move an order out of a terminal state — terminal outcomes are immutable.
 * Dead orders are reclaimed through the callbacks the owning [OrderWorkflows] supplies.
 */
internal class OrderStore(
    settings: OrderSettings,
    private val log: Logger,
    isReferenced: (String) -> Boolean,
    reclaim: (String) -> Unit,
) {
    val book = OrderBook(isReferenced, reclaim)
    val exposure = PendingExposureBook(book)
    val risk = BracketRiskRecorder(settings.trackRisk, settings.instruments)
    val stops = ManagedStopBook()
    val prices = ObservedPrices(settings.priceProvider)
    val closeTickets = EngineHeldCloseTickets()
    val siblings = SiblingLinks()
    val children = PendingChildBook()
    val brackets = BracketBook()
    val scaleOuts = ScaleOutBook()
    val stacks = StackTracker()
    val scaleOutRecovery = ScaleOutRecovery(scaleOuts, book, exposure, settings.clock)
    val snapshots = OrderStateSnapshots(settings.persistor, book, children, brackets, scaleOutRecovery, siblings, stops)
    private val onProtectionFailure = settings.onProtectionFailure
    private val clock = settings.clock

    /** Starts tracking [managed] and persists the change. */
    fun track(managed: ManagedOrder) {
        book.put(managed)
        managed.request.strategyId
            .takeIf { it.isNotBlank() }
            ?.let(snapshots::remember)
        snapshots.persistAll()
    }

    /** Applies [change] to [id]; false when the order is unknown or the transition is illegal. */
    fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ): Boolean {
        val current = book[id]
        if (current == null) {
            snapshots.persistAll()
            return false
        }
        val updated = change(current)
        // A terminal outcome is immutable. Same-state metadata updates remain valid: a
        // filled stack entry still receives child ids when its protection is attached.
        if (current.state.isTerminal && updated.state != current.state) {
            log.error(
                "ignoring illegal terminal transition {} -> {} for order {} — terminal outcomes are immutable",
                current.state,
                updated.state,
                id,
            )
            return false
        }
        book.put(updated)
        if (updated.state.isTerminal && !current.state.isTerminal) book.enqueueGc(id)
        snapshots.persistAll()
        return true
    }

    // An exit already tracked is left as is.
    private fun armEngineHeldExit(
        stop: OrderRequest,
        wrapperId: String,
        ticket: String,
    ) {
        if (book.contains(stop.id)) return
        val now = clock.now()
        book.put(
            ManagedOrder(
                id = stop.id,
                request = stop,
                state = OrderState.PENDING,
                parentClientOrderId = wrapperId,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        closeTickets[stop.id] = ticket
        exposure.register(stop)
        snapshots.persistAll()
    }

    /**
     * Protects a venue position whose fill-anchored exits the venue refused to attach: an
     * engine-held [stop] and/or [target] that close [ticket]. With both, whichever fires first
     * cancels the other. They are children of the bracket [wrapperId], so the one that fills
     * completes that bracket the way a venue-side close does.
     */
    fun armEngineHeldExits(
        wrapperId: String,
        stop: OrderRequest.Stop?,
        target: OrderRequest.IfTouched?,
        ticket: String,
    ) {
        stop?.let { armEngineHeldExit(it, wrapperId, ticket) }
        target?.let { armEngineHeldExit(it, wrapperId, ticket) }
        if (stop != null && target != null) {
            siblings.pair(stop.id, target.id)
            snapshots.persistAll()
        }
    }

    /** Raises an operator alert that a position is not protected as intended. */
    fun reportProtectionFailure(
        strategyId: String,
        message: String,
    ) {
        log.error("position protection failure: {}", message)
        runCatching { onProtectionFailure(strategyId, message) }
            .onFailure { log.error("stack protection alert failed for strategy {}", strategyId, it) }
    }
}
