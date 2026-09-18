package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.Trade
import com.qkt.positions.StrategyPositionTracker.FillApplication
import java.math.BigDecimal

/**
 * The netting-venue booking rule for a strategy's PRIMARY leg: a trade on the PRIMARY's side
 * averages in, the opposite side realizes PnL and reduces, flat-closes or flips it. Holds no
 * state of its own; it rewrites books in [legBooks], then reindexes the account, when the tracker calls it.
 */
internal class PrimaryNetting(
    private val legBooks: StrategyLegBooks,
    private val ids: PrimaryLegIds,
    private val accountIndex: AccountNetIndex,
    private val excursions: PrimaryExcursions,
) {
    /** Book a [LegIntent.Net] fill: net it via [apply], then re-anchor the PRIMARY's excursion. */
    fun netFill(event: BrokerEvent.OrderFilled): FillApplication {
        val trade =
            Trade(
                orderId = event.clientOrderId,
                symbol = event.symbol,
                price = event.price,
                quantity = event.quantity,
                side = event.side,
                timestamp = event.timestamp,
            )
        val realized = apply(event.strategyId, trade, event.brokerOrderId)
        excursions.sync(event.strategyId, event.symbol)
        return FillApplication(realized)
    }

    /** See [StrategyPositionTracker.apply]: [net] then reindex the account. */
    fun apply(
        strategyId: String,
        trade: Trade,
        brokerTicket: String?,
    ): BigDecimal {
        val realized = net(strategyId, trade, brokerTicket)
        accountIndex.reindex(trade.symbol)
        return realized
    }

    /** Net [trade] into [strategyId]'s PRIMARY leg and return the realized PnL. */
    private fun net(
        strategyId: String,
        trade: Trade,
        brokerTicket: String?,
    ): BigDecimal {
        val books = legBooks.booksOrCreate(strategyId)
        val book = books.getOrPut(trade.symbol) { LegBook(trade.symbol) }
        val primary = book.primary()

        // No primary yet → open one with this trade.
        if (primary == null) {
            book.add(
                PositionLeg(
                    legId = ids.next(strategyId, trade.symbol),
                    symbol = trade.symbol,
                    side = trade.side,
                    quantity = trade.quantity,
                    entryPrice = trade.price,
                    openedAt = trade.timestamp,
                    role = LegRole.PRIMARY,
                    brokerTicket = brokerTicket,
                ),
            )
            return Money.ZERO
        }

        val sameDirection = primary.side == trade.side

        if (sameDirection) {
            // Average into the existing primary. Replace the leg with one carrying the
            // combined quantity + weighted entry, preserving openedAt.
            val totalQty = primary.quantity.add(trade.quantity)
            val newAvg =
                primary.entryPrice
                    .multiply(primary.quantity)
                    .add(trade.price.multiply(trade.quantity))
                    .divide(totalQty, Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            book.close(primary.legId)
            book.add(
                PositionLeg(
                    legId = ids.next(strategyId, trade.symbol),
                    symbol = trade.symbol,
                    side = primary.side,
                    quantity = totalQty,
                    entryPrice = newAvg,
                    openedAt = primary.openedAt,
                    role = LegRole.PRIMARY,
                    brokerTicket = if (primary.brokerTicket == brokerTicket) brokerTicket else null,
                ),
            )
            return Money.ZERO
        }

        // Opposite direction → realize PnL on the closed portion, then either reduce,
        // flat-close, or flip the primary.
        val closingQty = primary.quantity.min(trade.quantity)
        val priceDiff =
            if (primary.side == Side.BUY) {
                trade.price.subtract(primary.entryPrice)
            } else {
                primary.entryPrice.subtract(trade.price)
            }
        val realized = closingQty.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)

        book.close(primary.legId)
        val remainingPrimaryQty = primary.quantity.subtract(trade.quantity)
        when {
            remainingPrimaryQty.signum() == 0 -> {
                // Fully closed — primary removed, nothing to add.
            }
            remainingPrimaryQty.signum() > 0 -> {
                // Reduced — same side and entry price preserved.
                book.add(
                    PositionLeg(
                        legId = ids.next(strategyId, trade.symbol),
                        symbol = trade.symbol,
                        side = primary.side,
                        quantity = remainingPrimaryQty,
                        entryPrice = primary.entryPrice,
                        openedAt = primary.openedAt,
                        role = LegRole.PRIMARY,
                        brokerTicket = primary.brokerTicket,
                    ),
                )
            }
            else -> {
                // Flipped — new primary on opposite side with the remainder and the trade price.
                book.add(
                    PositionLeg(
                        legId = ids.next(strategyId, trade.symbol),
                        symbol = trade.symbol,
                        side = trade.side,
                        quantity = remainingPrimaryQty.abs(),
                        entryPrice = trade.price,
                        openedAt = trade.timestamp,
                        role = LegRole.PRIMARY,
                        brokerTicket = brokerTicket,
                    ),
                )
            }
        }
        if (book.isEmpty()) {
            books.remove(trade.symbol)
        }
        return realized
    }
}
