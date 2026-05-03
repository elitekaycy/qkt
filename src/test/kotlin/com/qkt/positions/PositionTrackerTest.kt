package com.qkt.positions

import com.qkt.common.Side
import com.qkt.execution.Trade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PositionTrackerTest {
    private val tracker = PositionTracker()

    private fun trade(
        symbol: String,
        qty: Double,
        side: Side,
        ts: Long = 1000L,
    ) = Trade(orderId = "ORD-X", symbol = symbol, price = 100.0, quantity = qty, side = side, timestamp = ts)

    @Test
    fun `positionFor returns null for unknown symbol`() {
        assertThat(tracker.positionFor("XAUUSD")).isNull()
    }

    @Test
    fun `apply Buy creates position with positive quantity`() {
        tracker.apply(trade("XAUUSD", qty = 1.5, side = Side.BUY))
        assertThat(tracker.positionFor("XAUUSD")).isEqualTo(Position("XAUUSD", 1.5))
    }

    @Test
    fun `apply Sell from zero creates short position with negative quantity`() {
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.SELL))
        assertThat(tracker.positionFor("XAUUSD")).isEqualTo(Position("XAUUSD", -2.0))
    }

    @Test
    fun `consecutive Buys accumulate quantity`() {
        tracker.apply(trade("XAUUSD", qty = 1.0, side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = 0.5, side = Side.BUY))
        assertThat(tracker.positionFor("XAUUSD")?.quantity).isEqualTo(3.5)
    }

    @Test
    fun `Sell that brings quantity to zero removes the position from the map`() {
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.SELL))
        assertThat(tracker.positionFor("XAUUSD")).isNull()
        assertThat(tracker.allPositions()).isEmpty()
    }

    @Test
    fun `tracks positions for multiple symbols independently`() {
        tracker.apply(trade("XAUUSD", qty = 1.0, side = Side.BUY))
        tracker.apply(trade("EURUSD", qty = 5.0, side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.BUY))
        assertThat(tracker.positionFor("XAUUSD")?.quantity).isEqualTo(3.0)
        assertThat(tracker.positionFor("EURUSD")?.quantity).isEqualTo(5.0)
        assertThat(tracker.allPositions()).hasSize(2)
    }
}
