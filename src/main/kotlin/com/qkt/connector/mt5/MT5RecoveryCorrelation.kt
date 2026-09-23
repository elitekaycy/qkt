package com.qkt.connector.mt5

import com.qkt.common.Side
import com.qkt.execution.ManagedOrder

/**
 * Joins restored orders to the venue's resting orders and positions after a restart.
 *
 * An exact join — the gateway placement id or the untruncated comment equals the order id — is
 * always trusted. A join on a truncated comment prefix is not, because the prefix names a strategy,
 * not an order: `dsl-gold_scale_burst_fixe` is a prefix of the seed `dsl-gold_scale_burst_fixed--0`
 * and of every `…--0-stack-tierN-entry` leg. Such a join is accepted only when the venue item is
 * the order's side, was not opened well before the order was sent, and no other restored order
 * could claim it the same way. So ten stale leg entries facing the seed's position adopt nothing,
 * while a leg that genuinely filled during downtime still recovers from its own position. A single
 * stale entry the same size and side as an unbooked look-alike is indistinguishable from venue
 * data alone; only the placement id or an untruncated comment can tell them apart.
 */
internal class MT5RecoveryCorrelation(
    orders: List<ManagedOrder>,
    private val pending: List<MT5PendingOrder>,
    private val positions: List<MT5Position>,
) {
    private val unsafePending: Set<Long> =
        claimedBy(orders, pending, { it.ticket }, atLeast = 1) { o, p -> exact(p.clientOrderId, p.comment, o.id) } +
            claimedBy(orders, pending, { it.ticket }, atLeast = 2) { o, p -> loosePending(o, p) }
    private val unsafePositions: Set<Long> =
        claimedBy(orders, positions, { it.ticket }, atLeast = 1) { o, p -> exact(p.clientOrderId, p.comment, o.id) } +
            claimedBy(orders, positions, { it.ticket }, atLeast = 2) { o, p -> loosePosition(o, p) }

    /** Resting orders that belong to [order]. */
    fun pendingFor(order: ManagedOrder): List<MT5PendingOrder> =
        pending.filter {
            exact(it.clientOrderId, it.comment, order.id) ||
                (loosePending(order, it) && it.ticket !in unsafePending)
        }

    /** True when [position] joins [order] by placement id or untruncated comment, not by prefix. */
    fun isExact(
        order: ManagedOrder,
        position: MT5Position,
    ): Boolean = exact(position.clientOrderId, position.comment, order.id)

    /** Open positions that belong to [order]. */
    fun positionsFor(order: ManagedOrder): List<MT5Position> =
        positions.filter {
            exact(it.clientOrderId, it.comment, order.id) ||
                (loosePosition(order, it) && it.ticket !in unsafePositions)
        }

    private fun loosePending(
        order: ManagedOrder,
        p: MT5PendingOrder,
    ): Boolean =
        !exact(p.clientOrderId, p.comment, order.id) &&
            matchesOrderComment(p.comment, order.id) &&
            notBefore(p.timeSetup, order)

    private fun loosePosition(
        order: ManagedOrder,
        p: MT5Position,
    ): Boolean =
        !exact(p.clientOrderId, p.comment, order.id) &&
            matchesOrderComment(p.comment, order.id) &&
            p.type == (if (order.request.side == Side.BUY) 0 else 1) &&
            notBefore(p.openTime, order)

    private fun notBefore(
        venueEpoch: Long,
        order: ManagedOrder,
    ): Boolean {
        if (venueEpoch <= 0L || order.request.timestamp <= 0L) return true
        val venueMs = if (venueEpoch < 100_000_000_000L) venueEpoch * 1_000L else venueEpoch
        return venueMs >= order.request.timestamp - VENUE_CLOCK_SKEW_MS
    }

    private companion object {
        /** How far the venue clock may run behind the engine clock and still count as after a send. */
        const val VENUE_CLOCK_SKEW_MS: Long = 5_000L

        fun exact(
            clientOrderId: String?,
            comment: String?,
            orderId: String,
        ): Boolean = clientOrderId == orderId || comment == orderId

        /**
         * Tickets joined by at least [atLeast] orders: one exact owner already rules out any
         * prefix claim, and two prefix claimants rule each other out.
         */
        fun <T> claimedBy(
            orders: List<ManagedOrder>,
            items: List<T>,
            ticket: (T) -> Long,
            atLeast: Int,
            joins: (ManagedOrder, T) -> Boolean,
        ): Set<Long> = items.filter { item -> orders.count { joins(it, item) } >= atLeast }.mapTo(HashSet(), ticket)
    }
}
