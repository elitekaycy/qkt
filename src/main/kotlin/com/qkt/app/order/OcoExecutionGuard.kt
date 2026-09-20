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
 * because closing by side alone could flatten an unrelated position.
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

    /** The already-filled sibling of the emulated leg [clientOrderId], if the OCO double-filled. */
    fun filledSibling(clientOrderId: String): ManagedOrder? {
        if (clientOrderId !in groupByLeg) return null
        return siblings[clientOrderId]
            .asSequence()
            .mapNotNull(book.orders::get)
            .firstOrNull { it.state == OrderState.FILLED }
    }

    /** Closes the position opened by [secondFill] after [firstFilledSibling] already filled. */
    fun compensateDoubleFill(
        secondFill: BrokerEvent.OrderFilled,
        firstFilledSibling: ManagedOrder,
    ) {
        val strategyId =
            secondFill.strategyId.ifBlank {
                book[secondFill.clientOrderId]?.request?.strategyId.orEmpty()
            }
        val positionTicket = secondFill.brokerOrderId?.takeIf { it.isNotBlank() }
        val groupId = groupByLeg.getValue(secondFill.clientOrderId)
        if (positionTicket == null) {
            ops.reportProtectionFailure(
                strategyId,
                "CRITICAL OCO invariant violated for $groupId: ${firstFilledSibling.id} and " +
                    "${secondFill.clientOrderId} both filled, but the second fill has no owned position ticket; " +
                    "automatic close was refused",
            )
            return
        }

        val compensationId = "$groupId-oco-double-fill-close-${secondFill.clientOrderId}"
        val secondPositionQuantity =
            book[secondFill.clientOrderId]?.cumulativeFilledQuantity?.takeIf { it.signum() > 0 }
                ?: secondFill.quantity
        ops.reportProtectionFailure(
            strategyId,
            "CRITICAL OCO invariant violated for $groupId: ${firstFilledSibling.id} and " +
                "${secondFill.clientOrderId} both filled; closing second position ticket $positionTicket",
        )
        compensations[compensationId] = Compensation(strategyId, positionTicket)
        val close =
            OrderRequest.Market(
                id = compensationId,
                symbol = secondFill.symbol,
                side = if (secondFill.side == Side.BUY) Side.SELL else Side.BUY,
                quantity = secondPositionQuantity,
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
    }
}
