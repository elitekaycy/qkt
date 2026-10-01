package com.qkt.broker.continuous

import com.qkt.broker.bookedLegs
import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

/**
 * A lane meeting its venue again after a restart ([recover]), then going on once the session is ready
 * ([ready]). The venue takes back every order the lane had out (engine orders under the venue ids they
 * work under, resting orders whose cancel is awaited, and roll legs), each with what of it the lane
 * already booked, so the venue replays only what the lane missed. An order the venue does not know never
 * reached it: an engine order is forgotten (the engine retires it), while a roll leg, or a roll's
 * re-placement of an order the engine still holds, is sent again under its own id. Once ready, every
 * awaited cancel is sent again and an engine order the engine no longer holds is cancelled.
 */
internal class LaneRecovery(
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val venue: ContractVenue,
    private val orders: ContinuousOrderMap,
    private val legs: RollLegs,
    private val rolls: RollExecutor,
    private val book: StrategyPositionTracker,
    private val space: (Int) -> PriceSpace,
) {
    private var recovered = false

    /** The legs and roll re-placements the venue never received, to send again once ready. */
    private val resend = ArrayList<OrderRequest>()

    /** The venue orders to cancel once ready: awaited cancels, and engine orders the engine no longer holds. */
    private val cancels = LinkedHashSet<String>()

    /**
     * Has the venue take back what the lane had out, beside the engine's restored [engineOrders]; returns
     * the engine ids the venue accounts for. Once per session.
     */
    fun recover(engineOrders: List<ManagedOrder>): Set<String> {
        check(!recovered) { "${chainOf().symbol} already recovered its venue orders" }
        recovered = true
        val out =
            (orders.all + legs.awaitedCancels).distinctBy { it.venueId }.map(::managed) + legs.inFlight.map(::managed)
        val restoredLegs = legs.inFlight.map { it.leg }
        val known = venue.broker.recoverPendingOrders(out)
        restoredLegs.filterTo(resend) { it.id !in known }
        for (awaited in legs.awaitedCancels) {
            if (awaited.venueId in known) cancels += awaited.venueId else legs.forgetCancel(awaited.venueId)
        }
        val held = engineOrders.mapTo(HashSet()) { it.id }
        for (order in orders.all) {
            val sent = order.venueId in known
            when {
                // A roll's re-placement is the lane's own act: one the venue never received goes again.
                !sent && order.replacements > 0 && order.request.id in held -> resend += contractRequest(order)
                !sent -> orders.removeByVenueId(order.venueId)
                order.request.id !in held -> cancels += order.venueId
            }
        }
        return engineOrders.mapNotNullTo(LinkedHashSet()) { o -> o.id.takeIf { orders.byEngineId(it) != null } }
    }

    /** The session is restored: the venue is told so, and what the lane held back is sent. */
    fun ready() {
        if (!recovered) recover(emptyList())
        venue.broker.watchBookedLegs { book.bookedLegs(book.allByStrategy().keys) }
        resend.forEach { venue.broker.submit(it) }
        cancels.forEach(venue.broker::cancel)
        resend.clear()
        cancels.clear()
        rolls.ready()
    }

    private fun managed(order: ContinuousOrder): ManagedOrder = managed(contractRequest(order), order.venueFilled)

    /** [order] as the venue order it works under. */
    private fun contractRequest(order: ContinuousOrder): OrderRequest {
        val contract = chainOf().contractSymbol(order.contractIndex)
        return requireNotNull(
            toContract(order.request, order.venueId, contract, space(order.contractIndex), order.placed),
        )
    }

    private fun managed(leg: LegInFlight) =
        managed(
            leg.leg,
            leg.slices.fold(BigDecimal.ZERO) { sum, slice -> sum + slice.quantity },
        )

    private fun managed(
        request: OrderRequest,
        filled: BigDecimal,
    ): ManagedOrder {
        val now = clock.now()
        return ManagedOrder(
            request.id,
            request,
            OrderState.WORKING,
            cumulativeFilledQuantity = filled,
            createdAt = now,
            lastUpdatedAt = now,
        )
    }
}
