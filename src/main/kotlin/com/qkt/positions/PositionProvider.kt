package com.qkt.positions

import com.qkt.common.Side
import com.qkt.execution.Trade

interface PositionProvider {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>
}

class PositionTracker : PositionProvider {
    private val positions = mutableMapOf<String, Position>()

    fun apply(trade: Trade) {
        val current = positions[trade.symbol]?.quantity ?: 0.0
        val delta = if (trade.side == Side.BUY) trade.quantity else -trade.quantity
        val next = current + delta
        if (next == 0.0) {
            positions.remove(trade.symbol)
        } else {
            positions[trade.symbol] = Position(trade.symbol, next)
        }
    }

    override fun positionFor(symbol: String): Position? = positions[symbol]

    override fun allPositions(): Map<String, Position> = positions.toMap()
}
