package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.exitLegIntent
import com.qkt.execution.isTerminal

/**
 * What a filled entry releases. A decomposed bracket whose exits are anchored on the fill gets
 * its exit OCO built now; an attached bracket gets its venue SL/TP re-anchored on the fill (with
 * an engine-held fallback stop should the venue refuse) and its managed stop armed; any other
 * parent dispatches the children it was holding.
 */
internal class BracketFills(
    private val book: OrderBook,
    private val brackets: BracketBook,
    private val exits: BracketExits,
    private val venueProtection: VenuePositionProtection,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    /** Arms whatever was waiting on the fill [e]; [pending] are the children held for it. */
    fun armExits(
        e: BrokerEvent.OrderFilled,
        pending: List<OrderRequest>?,
    ) {
        val fallbackBracket = brackets.fillAnchoredFallback.remove(e.clientOrderId)
        val attachedBracket = brackets.fillAnchoredAttached.remove(e.clientOrderId)
        when {
            fallbackBracket != null -> ops.dispatch(exits.exitOco(fallbackBracket, e.price, e.quantity))
            attachedBracket != null -> armAttached(e, attachedBracket, pending)
            else -> pending?.forEach { ops.dispatch(it) }
        }
    }

    private fun armAttached(
        e: BrokerEvent.OrderFilled,
        attachedBracket: OrderRequest.Bracket,
        pending: List<OrderRequest>?,
    ) {
        val resolved = resolveBracketAtFill(attachedBracket, e.price)
        val sl = stopPriceAtEntry(resolved, e.price)
        e.brokerOrderId
            ?.takeIf { it.isNotBlank() }
            ?.let { ticket ->
                val operationId = "bracket:${e.clientOrderId}:${e.sequenceId}"
                val fallbackStop =
                    if (resolved.stopLoss is StopLossSpec.Fixed) {
                        OrderRequest.Stop(
                            id = "${resolved.id}-sl",
                            symbol = resolved.symbol,
                            side = if (resolved.side == Side.BUY) Side.SELL else Side.BUY,
                            quantity = e.quantity,
                            stopPrice = sl,
                            timeInForce = resolved.timeInForce,
                            timestamp = clock.now(),
                            strategyId = resolved.strategyId,
                            legIntent = resolved.exitLegIntent(),
                        )
                    } else {
                        null
                    }
                venueProtection.attachBracket(
                    operationId,
                    ticket,
                    resolved.strategyId,
                    fallbackStop,
                    sl,
                    resolved.takeProfit,
                )
            }
        // A bracket restored before its symbol was quoted had no price to build its
        // engine-managed stop on; build it now from the fill it anchors to.
        val heldStop =
            if (resolved.stopLoss !is StopLossSpec.Fixed &&
                pending.orEmpty().none { it.id == "${resolved.id}-sl" } &&
                book["${resolved.id}-sl"]?.state?.isTerminal != false
            ) {
                exits.managedStop(resolved, clock.now(), entryPrice = e.price)?.also { stop ->
                    ops.track(
                        ManagedOrder(
                            id = stop.id,
                            request = stop,
                            state = OrderState.CREATED,
                            parentClientOrderId = resolved.id,
                            createdAt = clock.now(),
                            lastUpdatedAt = clock.now(),
                        ),
                    )
                }
            } else {
                null
            }
        (pending.orEmpty() + listOfNotNull(heldStop)).forEach { child ->
            val anchored =
                when (child) {
                    is OrderRequest.ArmedTrailingStop ->
                        child.copy(entryPrice = e.price, quantity = child.quantity.min(e.quantity))
                    is OrderRequest.SteppedStop ->
                        child.copy(
                            entryPrice = e.price,
                            quantity = child.quantity.min(e.quantity),
                            timestamp = clock.now(),
                        )
                    is OrderRequest.TimeTighteningStop ->
                        child.copy(
                            entryPrice = e.price,
                            quantity = child.quantity.min(e.quantity),
                            timestamp = clock.now(),
                        )
                    else -> child
                }
            ops.dispatch(anchored)
        }
    }
}
