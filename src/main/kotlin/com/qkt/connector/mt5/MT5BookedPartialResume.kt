package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Picks up, after a restart, an entry that had filled in part and whose filled part the ledger
 * already booked. The booked part is never published again; what the venue filled since is.
 * E.g. `entry-1` asked for 1.00, 0.40 was booked before the restart: with the residual still
 * resting and the position at 0.40 the residual is tracked again so its later fill books 0.60;
 * with the position grown to 0.70 while down, 0.30 is published now; with the residual gone the
 * remainder is cancelled, or the order completes if the venue filled all of it.
 */
internal class MT5BookedPartialResume(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val partialEntries: MT5PartialEntries,
    private val seedTrackedTickets: (Set<Long>) -> Unit,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    fun resume(
        order: ManagedOrder,
        meta: MT5TicketMeta,
        position: MT5Position,
        residual: MT5PendingOrder?,
    ) {
        val booked = order.cumulativeFilledQuantity.takeIf { it.signum() > 0 } ?: position.volume
        val bookedPrice = order.avgFillPrice ?: position.priceOpen
        log.info(
            "MT5Broker {} recovery: partial entry {} ticket={} already booked {}; venue holds {} residual={}",
            profile.name,
            order.id,
            position.ticket,
            booked.toPlainString(),
            position.volume.toPlainString(),
            residual?.ticket,
        )
        if (residual != null) {
            partialEntries.registerPartialEntry(
                PartialEntryState(
                    meta = meta,
                    residualTicket = residual.ticket,
                    positionTicket = position.ticket,
                    symbol = order.request.symbol,
                    side = order.request.side,
                    requestedQuantity = order.request.quantity,
                    cumulativeFilled = booked,
                    averageFillPrice = bookedPrice,
                ),
                openedAtMs = position.openTime,
            )
            seedTrackedTickets(setOf(residual.ticket))
            partialEntries.reconcilePartialEntry(position)
            return
        }
        val venueFilled = position.volume.min(order.request.quantity)
        val growth = venueFilled - booked
        if (growth.signum() > 0) {
            val notional = position.priceOpen.multiply(venueFilled).subtract(bookedPrice.multiply(booked))
            val price = notional.divide(growth, Money.CONTEXT).takeIf { it.signum() > 0 } ?: position.priceOpen
            if (venueFilled >= order.request.quantity) {
                bus.publish(fill(order, position, price, growth))
                return
            }
            bus.publish(
                BrokerEvent.OrderPartiallyFilled(
                    clientOrderId = order.id,
                    brokerOrderId = position.ticket.toString(),
                    symbol = order.request.symbol,
                    side = order.request.side,
                    price = price,
                    quantity = growth,
                    cumulativeFilled = venueFilled,
                    strategyId = order.request.strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = order.id,
                brokerOrderId = position.ticket.toString(),
                reason = "residual absent during partial-entry recovery",
                strategyId = order.request.strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    private fun fill(
        order: ManagedOrder,
        position: MT5Position,
        price: BigDecimal,
        quantity: BigDecimal,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = order.id,
        brokerOrderId = position.ticket.toString(),
        symbol = order.request.symbol,
        side = order.request.side,
        price = price,
        quantity = quantity,
        strategyId = order.request.strategyId,
        timestamp = clock.now(),
    )
}
