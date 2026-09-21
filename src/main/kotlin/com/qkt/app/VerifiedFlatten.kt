package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.VenueOrderCancel
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.observe.insights.TicketAttribution
import java.time.Duration

/**
 * An operator flatten that is checked against the venue, not against what the engine believes.
 *
 * Every position ticket the venue attributes to the strategy is closed by ticket, and every
 * resting order whose venue comment names the strategy is cancelled by ticket - including one
 * the engine never learned about because its placement response was lost: a `stop --flatten`
 * issued while that order's outcome was still UNKNOWN used to leave it resting at the venue
 * under a strategy that had just been stopped (#1234). Flat means the venue reports neither.
 */
internal class VerifiedFlatten(
    private val broker: Broker,
    private val attribution: TicketAttribution,
    private val clock: Clock,
    private val deployedIds: List<String>,
    private val engineFlatten: () -> Unit,
    private val pollMs: Long = LiveSession.FLATTEN_VERIFY_POLL_MS,
) {
    fun run(timeout: Duration): FlattenResult {
        val strategyId =
            deployedIds.firstOrNull() ?: return FlattenResult(false, detail = "session has no strategy owner")
        if (!broker.supportsPositionTickets) {
            engineFlatten()
            return FlattenResult(false, detail = "broker ${broker.name} cannot verify open position tickets")
        }
        val initial =
            runCatching { positionsOf(strategyId) }
                .getOrElse { return FlattenResult(false, detail = "broker position read failed: ${it.message}") }
        val initialTickets = (initial.first.map { it.ticket } + initial.second).distinct()
        for (ticket in initial.first) {
            val ack =
                runCatching { broker.submit(closeOf(ticket, strategyId)) }
                    .getOrElse {
                        val detail = "close submission failed for ticket ${ticket.ticket}: ${it.message}"
                        return FlattenResult(false, initialTickets, detail)
                    }
            if (!ack.accepted) {
                return FlattenResult(
                    false,
                    initialTickets,
                    "close rejected for ticket ${ticket.ticket}: ${ack.rejectReason}",
                )
            }
        }

        val deadline = System.nanoTime() + timeout.toNanos()
        var remaining = initialTickets
        while (true) {
            val resting = cancelRestingOrders(strategyId)
            val current =
                runCatching { positionsOf(strategyId) }
                    .getOrElse { return FlattenResult(false, remaining, "broker verification failed: ${it.message}") }
            remaining = (current.first.map { it.ticket } + current.second + resting).distinct()
            if (remaining.isEmpty()) return FlattenResult(verifiedFlat = true)
            if (System.nanoTime() >= deadline) {
                val orders = "resting orders ${resting.joinToString()}"
                val what = if (resting.isEmpty()) "open or unattributed position tickets" else orders
                return FlattenResult(false, remaining, "broker still reports $what")
            }
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return FlattenResult(false, remaining, "broker verification interrupted")
            }
        }
    }

    /** Cancels the strategy's resting orders by venue ticket; returns the tickets still at the venue before this pass. */
    private fun cancelRestingOrders(strategyId: String): List<String> {
        val owned =
            runCatching { broker.pendingOrders() }
                .getOrDefault(emptyList())
                .filter { attribution.fromComment(it.clientOrderId ?: it.comment, deployedIds) == strategyId }
        val venue = broker as? VenueOrderCancel
        owned.forEach { order -> runCatching { venue?.cancelVenueOrder(order.ticket) } }
        return owned.map { it.ticket }
    }

    private fun positionsOf(strategyId: String): Pair<List<BrokerPositionTicket>, List<String>> {
        val owned = mutableListOf<BrokerPositionTicket>()
        val ambiguous = mutableListOf<String>()
        for (ticket in broker.positionTickets()) {
            when (attribution.ownerOf(ticket.ticket) ?: attribution.fromComment(ticket.comment, deployedIds)) {
                strategyId -> owned.add(ticket)
                null -> ambiguous.add(ticket.ticket)
            }
        }
        return owned to ambiguous
    }

    private fun closeOf(
        ticket: BrokerPositionTicket,
        strategyId: String,
    ) = OrderRequest.Market(
        id = "operator-kill-${ticket.ticket}",
        symbol = ticket.symbol,
        side = if (ticket.side == Side.BUY) Side.SELL else Side.BUY,
        quantity = ticket.qty,
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
        strategyId = strategyId,
        closesTicket = ticket.ticket,
    )
}
