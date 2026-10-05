package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.execution.isTerminal

/**
 * Enforces "exactly one leg" for OCOs that qkt, not the venue, holds together. If both legs
 * execute anyway (the sibling cancel lost a race at the venue) the second position is closed by
 * ticket and an operator alert is raised; if it has no ticket the close is refused and alerted,
 * because closing by side alone could flatten an unrelated position. A leg that executed only in
 * part counts as executed: e.g. leg1 fills 0.4 of 1 and leg2 then fills, leg2 is closed; leg2 fills
 * 0.3 after leg1 filled and is then cancelled, those 0.3 are closed.
 */
internal class OcoExecutionGuard(
    private val book: OrderBook,
    private val siblings: SiblingLinks,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    private class Compensation(
        val strategyId: String,
        val positionTicket: String,
    )

    /** Group id for each leg of an OCO that qkt, rather than the venue, must enforce. */
    private val groupByLeg: MutableMap<String, String> = mutableMapOf()

    /** In-flight closes raised after both independently placed OCO legs executed. */
    private val compensations: MutableMap<String, Compensation> = mutableMapOf()

    /** Legs already closed as an OCO's second execution: they no longer count as its executed leg. */
    private val compensated: MutableSet<String> = mutableSetOf()

    /** Position ticket a partly filled leg's executions opened, for closing them if it ends cancelled. */
    private val partialTickets: MutableMap<String, String> = mutableMapOf()

    /** Registers [legId] as a leg of the engine-enforced OCO [groupId]. */
    fun markEmulated(
        legId: String,
        groupId: String,
    ) {
        groupByLeg[legId] = groupId
    }

    /**
     * True while [id] is a filled emulated leg whose sibling is still live: it must stay
     * tracked so a late second fill can still be identified and compensated.
     */
    fun isHoldingForSibling(id: String): Boolean =
        id in groupByLeg && siblings[id].any { siblingId -> book[siblingId]?.state?.isTerminal == false }

    /**
     * The sibling of the emulated leg [clientOrderId] that already executed (filled, or filled in
     * part) and was not itself closed as a second execution, if the OCO double-executed.
     */
    fun executedSibling(clientOrderId: String): ManagedOrder? {
        if (clientOrderId !in groupByLeg) return null
        return siblings[clientOrderId]
            .asSequence()
            .filter { it !in compensated }
            .mapNotNull(book.orders::get)
            .firstOrNull { it.state == OrderState.FILLED || it.cumulativeFilledQuantity.signum() > 0 }
    }

    /** The emulated leg [clientOrderId] filled in part, opening position [ticket]. */
    fun onPartialExecution(
        clientOrderId: String,
        ticket: String?,
    ) {
        if (clientOrderId in groupByLeg && !ticket.isNullOrBlank()) partialTickets[clientOrderId] = ticket
    }

    /**
     * The emulated leg [clientOrderId] was cancelled after filling in part: when its sibling had
     * already executed, closes what it filled. True when it was a second execution.
     */
    fun compensateCancelledExecution(clientOrderId: String): Boolean {
        val leg = book[clientOrderId] ?: return false
        if (leg.cumulativeFilledQuantity.signum() <= 0) return false
        val first = executedSibling(clientOrderId) ?: return false
        compensate(leg, first, partialTickets[clientOrderId], leg.cumulativeFilledQuantity, leg.request.strategyId)
        return true
    }

    /** Closes the position opened by [secondFill] after [firstFilledSibling] already executed. */
    fun compensateDoubleFill(
        secondFill: BrokerEvent.OrderFilled,
        firstFilledSibling: ManagedOrder,
    ) {
        val second = book[secondFill.clientOrderId] ?: return
        val quantity = second.cumulativeFilledQuantity.takeIf { it.signum() > 0 } ?: secondFill.quantity
        val ticket = secondFill.brokerOrderId?.takeIf { it.isNotBlank() } ?: partialTickets[second.id]
        compensate(
            second,
            firstFilledSibling,
            ticket,
            quantity,
            secondFill.strategyId.ifBlank { second.request.strategyId },
        )
    }

    private fun compensate(
        second: ManagedOrder,
        first: ManagedOrder,
        positionTicket: String?,
        quantity: java.math.BigDecimal,
        strategyId: String,
    ) {
        compensated += second.id
        val groupId = groupByLeg.getValue(second.id)
        if (positionTicket == null) {
            ops.reportProtectionFailure(
                strategyId,
                "CRITICAL OCO invariant violated for $groupId: ${first.id} and " +
                    "${second.id} both filled, but the second fill has no owned position ticket; " +
                    "automatic close was refused",
            )
            return
        }

        val compensationId = "$groupId-oco-double-fill-close-${second.id}"
        ops.reportProtectionFailure(
            strategyId,
            "CRITICAL OCO invariant violated for $groupId: ${first.id} and " +
                "${second.id} both filled; closing second position ticket $positionTicket",
        )
        compensations[compensationId] = Compensation(strategyId, positionTicket)
        val close =
            OrderRequest.Market(
                id = compensationId,
                symbol = second.request.symbol,
                side = if (second.request.side == Side.BUY) Side.SELL else Side.BUY,
                quantity = quantity,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
                strategyId = strategyId,
                closesTicket = positionTicket,
                legIntent = LegIntent.Close(ticket = positionTicket),
            )
        val ack = ops.submit(close)
        if (!ack.accepted) {
            compensations.remove(compensationId)?.let {
                ops.reportProtectionFailure(
                    strategyId,
                    "CRITICAL OCO compensation $compensationId was rejected for position $positionTicket: " +
                        (ack.rejectReason ?: "unknown reason"),
                )
            }
        }
    }

    /** A compensation close filled; nothing left to watch. */
    fun onFilled(clientOrderId: String) {
        compensations.remove(clientOrderId)
    }

    /** A compensation close was rejected: the double position is still open, so alert. */
    fun onRejected(
        clientOrderId: String,
        reason: String,
    ) {
        compensations.remove(clientOrderId)?.let { compensation ->
            ops.reportProtectionFailure(
                compensation.strategyId,
                "CRITICAL OCO compensation $clientOrderId failed for position " +
                    "${compensation.positionTicket}: $reason",
            )
        }
    }

    /** Drops [id]'s group membership; its order was reclaimed. */
    fun forget(id: String) {
        groupByLeg.remove(id)
        compensated.remove(id)
        partialTickets.remove(id)
    }
}
