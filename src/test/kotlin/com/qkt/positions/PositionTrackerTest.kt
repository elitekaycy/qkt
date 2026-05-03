package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PositionTrackerTest {
    private val tracker = PositionTracker()

    private fun trade(
        symbol: String,
        qty: BigDecimal,
        side: Side,
        ts: Long = 1000L,
    ) = Trade(orderId = "ORD-X", symbol = symbol, price = Money.of("100"), quantity = qty, side = side, timestamp = ts)

    @Test
    fun `positionFor returns null for unknown symbol`() {
        assertThat(tracker.positionFor("XAUUSD")).isNull()
    }

    @Test
    fun `apply Buy creates position with positive quantity`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1.5"), side = Side.BUY))
        val position = tracker.positionFor("XAUUSD")
        assertThat(position?.symbol).isEqualTo("XAUUSD")
        assertThat(position?.quantity).isEqualByComparingTo(Money.of("1.5"))
    }

    @Test
    fun `apply Sell from zero creates short position with negative quantity`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.SELL))
        val position = tracker.positionFor("XAUUSD")
        assertThat(position?.symbol).isEqualTo("XAUUSD")
        assertThat(position?.quantity).isEqualByComparingTo(Money.of("-2"))
    }

    @Test
    fun `consecutive Buys accumulate quantity`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = Money.of("0.5"), side = Side.BUY))
        assertThat(tracker.positionFor("XAUUSD")?.quantity).isEqualByComparingTo(Money.of("3.5"))
    }

    @Test
    fun `Sell that brings quantity to zero removes the position from the map`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.SELL))
        assertThat(tracker.positionFor("XAUUSD")).isNull()
        assertThat(tracker.allPositions()).isEmpty()
    }

    @Test
    fun `tracks positions for multiple symbols independently`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY))
        tracker.apply(trade("EURUSD", qty = Money.of("5"), side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY))
        assertThat(tracker.positionFor("XAUUSD")?.quantity).isEqualByComparingTo(Money.of("3"))
        assertThat(tracker.positionFor("EURUSD")?.quantity).isEqualByComparingTo(Money.of("5"))
        assertThat(tracker.allPositions()).hasSize(2)
    }
}
