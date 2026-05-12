package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks per-strategy positions. Phase 27: internally backed by [LegBook]s so a single
 * strategy can hold a PRIMARY leg plus N STACK legs on the same symbol simultaneously.
 *
 * The public Position-returning API ([positionFor], [positionsFor], [allByStrategy])
 * continues to return the singular net view — strategies that don't use STACK_AT clauses
 * see no behavior change. The new [legBookFor] accessor exposes the leg-level view for
 * components that need to reason about individual legs (the stack engine, reconciliation).
 *
 * Stack legs are added via [addStackLeg] — they bypass the [apply] averaging logic which
 * would otherwise commingle them into the primary's entry-price math.
 */
class StrategyPositionTracker {
    private val byStrategy: MutableMap<String, MutableMap<String, LegBook>> = ConcurrentHashMap()

    /** Monotonic counter for engine-internal PRIMARY leg ids. */
    private val primaryLegSeq = AtomicLong()

    fun applyFill(event: BrokerEvent.OrderFilled): BigDecimal {
        if (event.strategyId.isBlank()) return Money.ZERO

        val trade =
            Trade(
                orderId = event.clientOrderId,
                symbol = event.symbol,
                price = event.price,
                quantity = event.quantity,
                side = event.side,
                timestamp = event.timestamp,
            )
        return apply(event.strategyId, trade)
    }

    fun apply(
        strategyId: String,
        trade: Trade,
    ): BigDecimal {
        val books = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(trade.symbol) { LegBook(trade.symbol) }
        val primary = book.primary()

        // No primary yet → open one with this trade.
        if (primary == null) {
            book.add(
                PositionLeg(
                    legId = nextPrimaryId(strategyId, trade.symbol),
                    symbol = trade.symbol,
                    side = trade.side,
                    quantity = trade.quantity,
                    entryPrice = trade.price,
                    openedAt = trade.timestamp,
                    role = LegRole.PRIMARY,
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
                    legId = nextPrimaryId(strategyId, trade.symbol),
                    symbol = trade.symbol,
                    side = primary.side,
                    quantity = totalQty,
                    entryPrice = newAvg,
                    openedAt = primary.openedAt,
                    role = LegRole.PRIMARY,
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
                        legId = nextPrimaryId(strategyId, trade.symbol),
                        symbol = trade.symbol,
                        side = primary.side,
                        quantity = remainingPrimaryQty,
                        entryPrice = primary.entryPrice,
                        openedAt = primary.openedAt,
                        role = LegRole.PRIMARY,
                    ),
                )
            }
            else -> {
                // Flipped — new primary on opposite side with the remainder and the trade price.
                book.add(
                    PositionLeg(
                        legId = nextPrimaryId(strategyId, trade.symbol),
                        symbol = trade.symbol,
                        side = trade.side,
                        quantity = remainingPrimaryQty.abs(),
                        entryPrice = trade.price,
                        openedAt = trade.timestamp,
                        role = LegRole.PRIMARY,
                    ),
                )
            }
        }
        if (book.isEmpty()) {
            books.remove(trade.symbol)
        }
        return realized
    }

    /**
     * Add a STACK leg directly. Used by the stack engine when a `STACK_AT` clause fires —
     * the resulting fill must NOT be averaged into the primary by [apply].
     */
    fun addStackLeg(
        strategyId: String,
        leg: PositionLeg,
    ) {
        require(leg.role == LegRole.STACK) { "addStackLeg requires LegRole.STACK; got ${leg.role}" }
        val books = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(leg.symbol) { LegBook(leg.symbol) }
        book.add(leg)
    }

    /**
     * Close a specific leg by id. Used when a STACK leg's own bracket fires, or when
     * external reconciliation closes a position. Returns the closed leg, or null if not found.
     */
    fun closeLeg(
        strategyId: String,
        symbol: String,
        legId: String,
    ): PositionLeg? {
        val book = byStrategy[strategyId]?.get(symbol) ?: return null
        val closed = book.close(legId)
        if (book.isEmpty()) {
            byStrategy[strategyId]?.remove(symbol)
        }
        return closed
    }

    fun positionFor(
        strategyId: String,
        symbol: String,
    ): Position? = byStrategy[strategyId]?.get(symbol)?.netView()

    fun positionsFor(strategyId: String): Map<String, Position> =
        byStrategy[strategyId]
            ?.mapNotNull { (sym, book) -> book.netView()?.let { sym to it } }
            ?.toMap()
            ?: emptyMap()

    fun allByStrategy(): Map<String, Map<String, Position>> =
        byStrategy.mapValues { (_, books) ->
            books.mapNotNull { (sym, book) -> book.netView()?.let { sym to it } }.toMap()
        }

    /** New Phase 27 accessor: the full leg book for direct inspection. */
    fun legBookFor(
        strategyId: String,
        symbol: String,
    ): LegBook? = byStrategy[strategyId]?.get(symbol)

    fun driftFor(
        symbol: String,
        brokerView: PositionProvider,
    ): BigDecimal {
        val strategySum =
            byStrategy.values.fold(Money.ZERO) { acc, books ->
                acc.add(books[symbol]?.netQuantity() ?: Money.ZERO)
            }
        val broker = brokerView.positionFor(symbol)?.quantity ?: Money.ZERO
        return strategySum.subtract(broker).setScale(Money.SCALE, Money.ROUNDING)
    }

    private fun nextPrimaryId(
        strategyId: String,
        symbol: String,
    ): String = "$strategyId-$symbol-primary-${primaryLegSeq.incrementAndGet()}"
}
