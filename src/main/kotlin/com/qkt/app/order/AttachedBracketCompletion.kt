package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal
import java.math.BigDecimal

/**
 * Completes a venue-attached bracket once its position is closed. Such a bracket has no resting
 * exit orders — the venue closes the ticket when SL/TP is hit and reports it under the entry id,
 * or an engine-held exit closes it by ticket. Once the closed quantity covers the fill, any
 * engine-held stop armed against the ticket is released, held children are cancelled, and the
 * wrapper goes terminal so it stops being persisted and can be reclaimed.
 */
internal class AttachedBracketCompletion(
    private val book: OrderBook,
    private val brackets: BracketBook,
    private val closeTickets: EngineHeldCloseTickets,
    private val exposure: PendingExposureBook,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    /**
     * The venue closed (part of) an attached bracket's position. A wrapper with a child still
     * live on the venue is left alone; its own terminal event completes it.
     */
    fun onVenueClose(e: BrokerEvent.OrderFilled) {
        val entry = book[e.clientOrderId] ?: return
        if (entry.request !is OrderRequest.Bracket || entry.state != OrderState.FILLED) return
        val filled = entry.cumulativeFilledQuantity.takeIf { it.signum() > 0 } ?: entry.request.quantity
        onExit(
            entryId = entry.id,
            wrapperId = entry.parentClientOrderId,
            filledQuantity = filled,
            closedQuantity = e.quantity,
            closeTicket = e.brokerOrderId ?: entry.brokerOrderId,
        )
    }

    /**
     * An attached bracket's entry ended without a fill (rejected, cancelled, expired): the wrapper
     * ends with it in [state], and any held child it armed is cancelled, so neither is persisted
     * as a pending order and re-placed after a restart.
     */
    fun onEntryEnded(
        entryId: String,
        state: OrderState,
    ) {
        val entry = book[entryId] ?: return
        if (entry.request !is OrderRequest.Bracket) return
        val wrapperId = entry.parentClientOrderId ?: return
        val wrapper = book[wrapperId] ?: return
        if (wrapper.request !is OrderRequest.Bracket || wrapper.state.isTerminal) return
        for (childId in wrapper.childClientOrderIds) {
            val child = book[childId] ?: continue
            if (child.state == OrderState.PENDING || child.state == OrderState.CREATED) ops.cancel(childId)
        }
        if (wrapper.childClientOrderIds.any { book[it]?.state?.isTerminal == false }) return
        ops.update(wrapperId) { it.copy(state = state, lastUpdatedAt = clock.now()) }
        exposure.remove(wrapperId)
    }

    /**
     * An engine-held exit child (`-sl` / `-tp`) of an attached bracket filled: the position it
     * protected is reduced or gone, exactly as after a venue-side close. The entry's own record
     * may already be reclaimed by then, so the filled quantity falls back to the requested size.
     */
    fun onEngineExit(e: BrokerEvent.OrderFilled) {
        if (!e.clientOrderId.endsWith("-sl") && !e.clientOrderId.endsWith("-tp")) return
        val wrapperId = book[e.clientOrderId]?.parentClientOrderId
        val wrapper = wrapperId?.let { book[it] }
        if (wrapper == null) {
            // Restored after a restart: the wrapper record is not persisted, but the attached
            // entry carries the position ticket this close-by-ticket just consumed.
            val ticket = e.brokerOrderId?.takeIf { it.isNotBlank() } ?: return
            val entry =
                book.orders.values.firstOrNull {
                    it.brokerOrderId == ticket &&
                        it.request is OrderRequest.Bracket &&
                        it.id == (it.request as OrderRequest.Bracket).entry.id
                } ?: return
            onExit(
                entryId = entry.id,
                wrapperId = null,
                filledQuantity = entry.cumulativeFilledQuantity.takeIf { it.signum() > 0 } ?: entry.request.quantity,
                closedQuantity = e.quantity,
                closeTicket = ticket,
            )
            return
        }
        val request = wrapper.request as? OrderRequest.Bracket ?: return
        if (wrapper.state.isTerminal) return
        val entryId = request.entry.id
        val entry = book[entryId]
        if (entry != null && entry.request !is OrderRequest.Bracket) return
        val filled = entry?.cumulativeFilledQuantity?.takeIf { it.signum() > 0 } ?: request.quantity
        onExit(
            entryId = entryId,
            wrapperId = wrapperId,
            filledQuantity = filled,
            closedQuantity = e.quantity,
            closeTicket = e.brokerOrderId ?: entry?.brokerOrderId,
        )
    }

    private fun onExit(
        entryId: String,
        wrapperId: String?,
        filledQuantity: BigDecimal,
        closedQuantity: BigDecimal,
        closeTicket: String?,
    ) {
        val closed = (brackets.venueClosedQuantityByEntry[entryId] ?: BigDecimal.ZERO) + closedQuantity
        if (closed < filledQuantity) {
            brackets.venueClosedQuantityByEntry[entryId] = closed
            return
        }
        brackets.venueClosedQuantityByEntry.remove(entryId)
        if (closeTicket != null) {
            for (id in closeTickets.stopsClosing(closeTicket)) {
                val managed = book[id] ?: continue
                if (managed.state == OrderState.PENDING || managed.state == OrderState.CREATED) ops.cancel(id)
            }
        }
        if (wrapperId == null) return
        val wrapper = book[wrapperId] ?: return
        if (wrapper.state.isTerminal) return
        for (childId in wrapper.childClientOrderIds) {
            val child = book[childId] ?: continue
            if (child.state == OrderState.PENDING || child.state == OrderState.CREATED) ops.cancel(childId)
        }
        val liveChild = wrapper.childClientOrderIds.any { book[it]?.state?.isTerminal == false }
        if (liveChild) return
        ops.update(wrapperId) { it.copy(state = OrderState.FILLED, lastUpdatedAt = clock.now()) }
        exposure.remove(wrapperId)
    }
}
