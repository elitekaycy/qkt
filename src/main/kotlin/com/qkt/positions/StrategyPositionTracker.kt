package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class StrategyPositionTracker {
    private val byStrategy: MutableMap<String, MutableMap<String, Position>> = ConcurrentHashMap()

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
        val positions = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val current = positions[trade.symbol]
        val signedTradeQty = if (trade.side == Side.BUY) trade.quantity else trade.quantity.negate()

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

    fun positionFor(
        strategyId: String,
        symbol: String,
    ): Position? = byStrategy[strategyId]?.get(symbol)

    fun positionsFor(strategyId: String): Map<String, Position> = byStrategy[strategyId]?.toMap() ?: emptyMap()

    fun allByStrategy(): Map<String, Map<String, Position>> = byStrategy.mapValues { it.value.toMap() }

    fun driftFor(
        symbol: String,
        brokerView: PositionProvider,
    ): BigDecimal {
        val strategySum =
            byStrategy.values.fold(Money.ZERO) { acc, byMap ->
                acc.add(byMap[symbol]?.quantity ?: Money.ZERO)
            }
        val broker = brokerView.positionFor(symbol)?.quantity ?: Money.ZERO
        return strategySum.subtract(broker).setScale(Money.SCALE, Money.ROUNDING)
    }
}
