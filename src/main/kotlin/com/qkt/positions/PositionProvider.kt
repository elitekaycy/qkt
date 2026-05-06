package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.Trade
import java.math.BigDecimal

interface PositionProvider {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>
}

class PositionTracker : PositionProvider {
    private val positions = mutableMapOf<String, Position>()

    fun applyFill(event: BrokerEvent.OrderFilled): BigDecimal {
        val trade =
            Trade(
                orderId = event.clientOrderId,
                symbol = event.symbol,
                price = event.price,
                quantity = event.quantity,
                side = event.side,
                timestamp = event.timestamp,
            )
        return apply(trade)
    }

    fun apply(trade: Trade): BigDecimal {
        val current = positions[trade.symbol]
        val signedTradeQty =
            if (trade.side == Side.BUY) trade.quantity else trade.quantity.negate()

        if (current == null) {
            positions[trade.symbol] = Position(trade.symbol, signedTradeQty, trade.price)
            return Money.ZERO
        }

        val currentQty = current.quantity
        val currentAvg = current.avgEntryPrice
        val sameDirection = currentQty.signum() == signedTradeQty.signum()

        if (sameDirection) {
            val totalQty = currentQty.add(signedTradeQty)
            val newAvg =
                currentAvg
                    .multiply(currentQty.abs())
                    .add(trade.price.multiply(trade.quantity))
                    .divide(totalQty.abs(), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            positions[trade.symbol] = Position(trade.symbol, totalQty, newAvg)
            return Money.ZERO
        }

        val closingQty = currentQty.abs().min(trade.quantity)
        val priceDiff =
            if (currentQty.signum() > 0) {
                trade.price.subtract(currentAvg)
            } else {
                currentAvg.subtract(trade.price)
            }
        val realized = closingQty.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)

        val remainingQty = currentQty.add(signedTradeQty)
        when {
            remainingQty.signum() == 0 -> positions.remove(trade.symbol)
            remainingQty.signum() == currentQty.signum() ->
                positions[trade.symbol] = Position(trade.symbol, remainingQty, currentAvg)
            else ->
                positions[trade.symbol] = Position(trade.symbol, remainingQty, trade.price)
        }
        return realized
    }

    fun reset(
        symbol: String,
        qty: BigDecimal,
        avgPx: BigDecimal,
    ) {
        if (qty.signum() == 0) {
            positions.remove(symbol)
        } else {
            positions[symbol] = Position(symbol, qty, avgPx)
        }
    }

    override fun positionFor(symbol: String): Position? = positions[symbol]

    override fun allPositions(): Map<String, Position> = positions.toMap()
}
