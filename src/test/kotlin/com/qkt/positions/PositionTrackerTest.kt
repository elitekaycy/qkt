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
        price: BigDecimal = Money.of("100"),
        ts: Long = 1000L,
    ) = Trade(orderId = "ORD-X", symbol = symbol, price = price, quantity = qty, side = side, timestamp = ts)

    @Test
    fun `positionFor returns null for unknown symbol`() {
        assertThat(tracker.positionFor("XAUUSD")).isNull()
    }

    @Test
    fun `apply Buy creates position with positive quantity and avgEntryPrice equal to trade price`() {
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("1.5"), side = Side.BUY, price = Money.of("100")))
        assertThat(realized).isEqualByComparingTo(Money.ZERO)
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("1.5"))
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `apply Sell from zero creates short position with negative quantity and avgEntryPrice equal to trade price`() {
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.SELL, price = Money.of("100")))
        assertThat(realized).isEqualByComparingTo(Money.ZERO)
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("-2"))
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `consecutive Buys accumulate quantity and update weighted-average price`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("100")))
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("110")))
        tracker.apply(trade("XAUUSD", qty = Money.of("0.5"), side = Side.BUY, price = Money.of("120")))
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("2.5"))
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("108"))
    }

    @Test
    fun `Sell that brings quantity to zero removes the position from the map`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY, price = Money.of("100")))
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.SELL, price = Money.of("110")))
        assertThat(realized).isEqualByComparingTo(Money.of("20"))
        assertThat(tracker.positionFor("XAUUSD")).isNull()
        assertThat(tracker.allPositions()).isEmpty()
    }

    @Test
    fun `tracks positions for multiple symbols independently`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("100")))
        tracker.apply(trade("EURUSD", qty = Money.of("5"), side = Side.BUY, price = Money.of("1.10")))
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY, price = Money.of("100")))
        assertThat(tracker.positionFor("XAUUSD")?.quantity).isEqualByComparingTo(Money.of("3"))
        assertThat(tracker.positionFor("EURUSD")?.quantity).isEqualByComparingTo(Money.of("5"))
        assertThat(tracker.allPositions()).hasSize(2)
    }

    @Test
    fun `weighted-average price updates on subsequent buys`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("100")))
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("110")))
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("105"))
    }

    @Test
    fun `partial sell does not change avgEntryPrice and returns realized PnL`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY, price = Money.of("100")))
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.SELL, price = Money.of("110")))
        assertThat(realized).isEqualByComparingTo(Money.of("10"))
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("1"))
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `full close removes position and returns realized PnL`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("100")))
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.SELL, price = Money.of("120")))
        assertThat(realized).isEqualByComparingTo(Money.of("20"))
        assertThat(tracker.positionFor("XAUUSD")).isNull()
    }

    @Test
    fun `flipping trade realizes PnL on closed portion and opens new position at trade price`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY, price = Money.of("100")))
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("5"), side = Side.SELL, price = Money.of("110")))
        assertThat(realized).isEqualByComparingTo(Money.of("20"))
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("-3"))
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("110"))
    }
}
