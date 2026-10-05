package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import java.math.BigDecimal

/**
 * Follows an entry the venue filled only in part until it is whole or its residual order is gone,
 * e.g. 1.00 lot requested, 0.40 filled at once: each later growth of the position is published as
 * its own slice (0.35, then the final 0.25 as `OrderFilled`), priced from the change in the venue's
 * average. Every change to the partial-entry maps happens under the broker's pending-transition
 * lock, together with the pending and position books.
 */
internal class MT5PartialEntries(
    private val books: MT5BrokerState,
    private val bus: EventBus,
    private val clock: Clock,
) {
    fun registerPartialEntry(
        state: PartialEntryState,
        openedAtMs: Long,
    ): MT5Position? =
        synchronized(books.pendingTransitionLock) {
            books.pendingBook.register(state.residualTicket, state.meta)
            books.partialEntryByPositionTicket[state.positionTicket] = state
            books.partialPositionByResidualTicket[state.residualTicket] = state.positionTicket
            books.positionBook.track(state.positionTicket, state.meta, state.symbol, openedAtMs)
            books.earlyPositionByTicket.remove(state.positionTicket)
        }

    fun onPositionIncreased(
        previous: MT5Position,
        latest: MT5Position,
    ) {
        if (latest.volume <= previous.volume) return
        reconcilePartialEntry(latest)
    }

    /**
     * Advances a partially filled entry from the position poller's cumulative venue volume.
     * Returns true when [position] belongs to a partial entry, including a duplicate snapshot.
     */
    fun reconcilePartialEntry(position: MT5Position): Boolean =
        advance(position.ticket, position.volume, position.priceOpen, position.openTime, fromHistory = false)

    fun isPartialEntry(positionTicket: Long): Boolean =
        synchronized(books.pendingTransitionLock) { positionTicket in books.partialEntryByPositionTicket }

    /**
     * Advances the entry on [positionTicket] to the [opened] lots its opening deals show at [averagePrice].
     * What this books beyond the poller's last snapshot is held for [takeUnseenGrowth].
     */
    fun advanceFromHistory(
        positionTicket: Long,
        opened: BigDecimal,
        averagePrice: BigDecimal,
        openedAtMs: Long,
    ): Boolean = advance(positionTicket, opened, averagePrice, openedAtMs, fromHistory = true)

    /** Entry lots booked from deal history that the position poller has not seen in a snapshot. */
    fun takeUnseenGrowth(positionTicket: Long): BigDecimal =
        synchronized(books.pendingTransitionLock) { unseenGrowth.remove(positionTicket) } ?: BigDecimal.ZERO

    private val unseenGrowth = HashMap<Long, BigDecimal>()

    private fun advance(
        positionTicket: Long,
        venueVolume: BigDecimal,
        venueAveragePrice: BigDecimal,
        openedAtMs: Long,
        fromHistory: Boolean,
    ): Boolean {
        var event: BrokerEvent? = null
        synchronized(books.pendingTransitionLock) {
            val state = books.partialEntryByPositionTicket[positionTicket] ?: return false
            val venueCumulative = venueVolume.min(state.requestedQuantity)
            if (venueCumulative <= state.cumulativeFilled) return true

            val sliceQuantity = venueCumulative - state.cumulativeFilled
            val slicePrice = incrementalEntryPrice(state, venueCumulative, venueAveragePrice, sliceQuantity)
            books.positionBook.setOpenedAt(positionTicket, openedAtMs)
            if (fromHistory) unseenGrowth.merge(positionTicket, sliceQuantity, BigDecimal::add)
            if (venueCumulative >= state.requestedQuantity) {
                books.partialEntryByPositionTicket.remove(positionTicket)
                books.partialPositionByResidualTicket.remove(state.residualTicket, positionTicket)
                books.pendingBook.forgetIfStill(state.meta.orderId, state.residualTicket, state.meta)
                books.recentlyFilledTickets[state.residualTicket] = clock.now()
                event =
                    BrokerEvent.OrderFilled(
                        clientOrderId = state.meta.orderId,
                        brokerOrderId = positionTicket.toString(),
                        symbol = state.symbol,
                        side = state.side,
                        price = slicePrice,
                        quantity = sliceQuantity,
                        strategyId = state.meta.strategyId,
                        timestamp = clock.now(),
                    )
            } else {
                books.partialEntryByPositionTicket[positionTicket] =
                    state.copy(
                        cumulativeFilled = venueCumulative,
                        averageFillPrice = venueAveragePrice,
                    )
                event =
                    BrokerEvent.OrderPartiallyFilled(
                        clientOrderId = state.meta.orderId,
                        brokerOrderId = positionTicket.toString(),
                        symbol = state.symbol,
                        side = state.side,
                        price = slicePrice,
                        quantity = sliceQuantity,
                        cumulativeFilled = venueCumulative,
                        strategyId = state.meta.strategyId,
                        timestamp = clock.now(),
                    )
            }
        }
        event?.let(bus::publish)
        return true
    }

    private fun incrementalEntryPrice(
        state: PartialEntryState,
        venueCumulative: BigDecimal,
        venueAveragePrice: BigDecimal,
        sliceQuantity: BigDecimal,
    ): BigDecimal {
        val venueNotional = venueAveragePrice.multiply(venueCumulative)
        val priorNotional = state.averageFillPrice.multiply(state.cumulativeFilled)
        val slicePrice = venueNotional.subtract(priorNotional).divide(sliceQuantity, com.qkt.common.Money.CONTEXT)
        return slicePrice.takeIf { it.signum() > 0 } ?: venueAveragePrice
    }

    fun cancelPartialEntryResidual(
        ticket: Long,
        reason: String,
    ) {
        val state =
            synchronized(books.pendingTransitionLock) {
                val positionTicket = books.partialPositionByResidualTicket.remove(ticket) ?: return
                val current = books.partialEntryByPositionTicket.remove(positionTicket) ?: return
                books.pendingBook.forgetIfStill(current.meta.orderId, ticket, current.meta)
                current
            }
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = state.meta.orderId,
                brokerOrderId = ticket.toString(),
                reason = reason,
                strategyId = state.meta.strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    fun removePartialEntryByResidualTicket(residualTicket: Long) {
        val positionTicket = books.partialPositionByResidualTicket.remove(residualTicket) ?: return
        books.partialEntryByPositionTicket.remove(positionTicket)
    }
}
