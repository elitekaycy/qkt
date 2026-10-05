package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Settles a resting order that left `/orders` without showing up in `/positions`, from the deal
 * history of the position it would have opened (MT5 numbers that position after the order). A
 * fill that was already closed again, by its stop, its target or a stop-out between two polls or
 * while qkt was down, is replayed as the entry fill plus its closing deals; e.g. BUY_STOP 9001
 * filled at 1.1200 and stopped out at 1.1150 books both legs rather than an `OrderCancelled`.
 */
internal class MT5VanishedPendingReplay(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val mt5Symbol: MT5Symbol,
    private val books: MT5BrokerState,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /** What deal history established about the vanished order. */
    enum class Outcome {
        /** The order filled and its position is already closed; both legs are published. */
        FILLED_AND_CLOSED,

        /** Unreadable history, or a close not fully in history yet: ask again next round. */
        UNKNOWN,

        /** A clean read with no opening deal: the order never executed. */
        NOT_FILLED,
    }

    fun settle(
        ticket: Long,
        meta: MT5TicketMeta,
    ): Outcome {
        val now = clock.now()
        val deals = client.getPositionDeals(ticket, now - HISTORY_LOOKBACK_MS, now) ?: return Outcome.UNKNOWN
        val opening = deals.filter { it.entry == 0 && it.volume.signum() > 0 && it.price.signum() > 0 }
        if (opening.isEmpty()) return Outcome.NOT_FILLED
        val quantity = opening.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        val price = MT5UnknownOutcomeMatching.weightedDealPrice(opening) ?: return Outcome.UNKNOWN
        val closing = deals.filter { it.entry != 0 && it.volume.signum() > 0 && it.price.signum() > 0 }
        val closed = closing.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        if (closed < quantity) {
            log.warn(
                "MT5Broker {} order {} ticket {} filled {} but history shows only {} closed; asking again",
                profile.name,
                meta.orderId,
                ticket,
                quantity.toPlainString(),
                closed.toPlainString(),
            )
            return Outcome.UNKNOWN
        }
        books.pendingBook.forgetTicket(ticket)
        val symbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(opening.first().symbol)}"
        log.warn(
            "MT5Broker {} order {} ticket {} filled and closed between polls; booking it from deal history",
            profile.name,
            meta.orderId,
            ticket,
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = meta.orderId,
                brokerOrderId = ticket.toString(),
                symbol = symbol,
                side = sideOf(opening.first()),
                price = price,
                quantity = quantity,
                strategyId = meta.strategyId,
                timestamp = now,
            ),
        )
        val venueCosts = books.venueCostLedger.book(ticket, deals, positionClosed = true, nowMs = now)
        val ordered = closing.sortedBy { it.timeMs }
        ordered.forEachIndexed { index, deal ->
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = meta.orderId,
                    brokerOrderId = ticket.toString(),
                    symbol = symbol,
                    side = sideOf(deal),
                    price = deal.price,
                    quantity = deal.volume,
                    strategyId = meta.strategyId,
                    timestamp = now,
                    updatesOrderExecution = false,
                    venueCosts = if (index == ordered.lastIndex) venueCosts else BigDecimal.ZERO,
                    exitReason = closingDealExitReason(listOf(deal)),
                ),
            )
        }
        return Outcome.FILLED_AND_CLOSED
    }

    private fun sideOf(deal: MT5Deal): Side = if (deal.type == 0) Side.BUY else Side.SELL

    private companion object {
        /** How far back the position's deals are searched; covers a GTC order resting for a month. */
        const val HISTORY_LOOKBACK_MS: Long = 31L * 24 * 60 * 60 * 1000
    }
}
