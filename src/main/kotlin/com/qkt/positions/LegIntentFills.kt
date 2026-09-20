package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.positions.StrategyPositionTracker.FillApplication
import com.qkt.positions.StrategyPositionTracker.LegAction
import java.math.BigDecimal

/**
 * Books execution slices that name one leg — [LegIntent.Open] extends or opens the leg the
 * order owns, [LegIntent.Close] closes or reduces the leg named by qkt id or venue ticket —
 * into [legBooks], guarding against duplicate venue reports. Holds no state of its own;
 * [StrategyPositionTracker.applyFillDetailed] calls it and does the persist and reindex.
 */
internal class LegIntentFills(
    private val legBooks: StrategyLegBooks,
    private val excursions: PrimaryExcursions,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(StrategyPositionTracker::class.java)

    fun open(
        event: BrokerEvent.OrderFilled,
        intent: LegIntent.Open,
        cumulativeFilled: BigDecimal?,
    ): FillApplication {
        val books = legBooks.booksOrCreate(event.strategyId)
        val book = books.getOrPut(event.symbol) { LegBook(event.symbol) }
        val ticket = event.brokerOrderId?.takeIf { it.isNotBlank() }
        // A venue ticket is one position and belongs to exactly one leg. A second leg claiming
        // it is a duplicate report (a replayed execution attributed to another order), not new
        // exposure.
        val owner = ticket?.let { book.legByTicket(it) }
        if (owner != null && owner.legId != intent.legId) {
            log.warn(
                "duplicate execution ignored: ticket {} on {} is already leg {} — {} for order {} books nothing",
                ticket,
                event.symbol,
                owner.legId,
                event.strategyId,
                event.clientOrderId,
            )
            return FillApplication(Money.ZERO, owner.legId, LegAction.OPENED, unbooked = true)
        }
        val existing = book.leg(intent.legId)
        if (existing == null) {
            book.add(
                PositionLeg(
                    legId = intent.legId,
                    symbol = event.symbol,
                    side = event.side,
                    quantity = event.quantity,
                    entryPrice = event.price,
                    openedAt = event.timestamp,
                    role = intent.role,
                    parentLegId = intent.parentLegId,
                    brokerTicket = ticket,
                ),
            )
            excursions.sync(event.strategyId, event.symbol)
            return FillApplication(Money.ZERO, intent.legId, LegAction.OPENED)
        }
        // The same order executing again: book only what the venue reports beyond what the
        // leg already holds. A cumulative at or below the booked quantity is a re-report.
        val sliceQuantity =
            if (cumulativeFilled != null && ticket != null && existing.brokerTicket == ticket) {
                val delta = cumulativeFilled.subtract(existing.quantity)
                if (delta.signum() <= 0) {
                    log.warn(
                        "duplicate execution ignored: leg {} on {} already holds {} of ticket {} (reported cumulative {})",
                        existing.legId,
                        event.symbol,
                        existing.quantity.toPlainString(),
                        ticket,
                        cumulativeFilled.toPlainString(),
                    )
                    return FillApplication(Money.ZERO, existing.legId, LegAction.OPENED, unbooked = true)
                }
                delta
            } else {
                event.quantity
            }
        mergeOwnedOpenSlice(
            book,
            existing.copy(
                quantity = sliceQuantity,
                entryPrice = event.price,
                openedAt = event.timestamp,
                brokerTicket = ticket,
            ),
        )
        return FillApplication(Money.ZERO, existing.legId, LegAction.OPENED)
    }

    /** Merge another execution slice into one stable owned leg without scanning the book. */
    private fun mergeOwnedOpenSlice(
        book: LegBook,
        slice: PositionLeg,
    ) {
        val existing = book.leg(slice.legId)
        if (existing == null) {
            book.add(slice)
            return
        }
        require(existing.symbol == slice.symbol && existing.side == slice.side) {
            "owned leg ${slice.legId} changed symbol or side across execution slices"
        }
        require(existing.role == slice.role && existing.parentLegId == slice.parentLegId) {
            "owned leg ${slice.legId} changed ownership across execution slices"
        }
        require(
            existing.brokerTicket == null ||
                slice.brokerTicket == null ||
                existing.brokerTicket == slice.brokerTicket,
        ) {
            "owned leg ${slice.legId} changed broker ticket across execution slices"
        }
        val totalQuantity = existing.quantity.add(slice.quantity)
        val averagePrice =
            existing.entryPrice
                .multiply(existing.quantity)
                .add(slice.entryPrice.multiply(slice.quantity))
                .divide(totalQuantity, Money.CONTEXT)
                .setScale(Money.SCALE, Money.ROUNDING)
        book.close(existing.legId)
        book.add(
            existing.copy(
                quantity = totalQuantity,
                entryPrice = averagePrice,
                brokerTicket = existing.brokerTicket ?: slice.brokerTicket,
            ),
        )
    }

    /**
     * Close (or reduce) the one leg [intent] names — by qkt id first, then by venue ticket.
     */
    fun close(
        event: BrokerEvent.OrderFilled,
        intent: LegIntent.Close,
    ): FillApplication {
        val book = legBooks.book(event.strategyId, event.symbol)
        val leg =
            book?.let { b ->
                intent.legId?.let { b.leg(it) } ?: intent.ticket?.let { b.legByTicket(it) }
            }
        if (book == null || leg == null) {
            // A close cannot create exposure: booking it against another leg would invent a
            // position the venue never opened. Nothing is booked; venue reconciliation owns the
            // correction.
            log.error(
                "close for {} on {} names leg {} / ticket {} the book does not hold; nothing booked",
                event.strategyId,
                event.symbol,
                intent.legId,
                intent.ticket,
            )
            return FillApplication(Money.ZERO, unbooked = true)
        }
        val closed = book.close(leg.legId) ?: return FillApplication(Money.ZERO, unbooked = true)
        val closingQty = closed.quantity.min(event.quantity)
        val priceDiff =
            if (closed.side == Side.BUY) {
                event.price.subtract(closed.entryPrice)
            } else {
                closed.entryPrice.subtract(event.price)
            }
        val realized = closingQty.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)
        val remaining = closed.quantity.subtract(closingQty)
        if (remaining.signum() > 0) book.add(closed.copy(quantity = remaining))
        if (book.isEmpty()) legBooks.booksOf(event.strategyId)?.remove(event.symbol)
        excursions.sync(event.strategyId, event.symbol)
        return FillApplication(realized, closed.legId, LegAction.CLOSED)
    }
}
