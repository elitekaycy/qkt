package com.qkt.broker

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MockBrokerTest {
    private val clock = FixedClock(time = 1000L)
    private val tracker = MarketPriceTracker()
    private val broker = MockBroker(clock, tracker)

    private fun marketOrder(
        id: String = "ORD-0",
        symbol: String = "XAUUSD",
        side: Side = Side.BUY,
        qty: BigDecimal = Money.of("1"),
        ts: Long = 1000L,
    ) = Order(id, symbol, side, qty, OrderType.MARKET, null, ts)

    @Test
    fun `fills MARKET order at tracker last price`() {
        tracker.update("XAUUSD", Money.of("2400.5"))
        val trade = broker.execute(marketOrder())
        assertThat(trade).isNotNull
        assertThat(trade!!.price).isEqualByComparingTo(Money.of("2400.5"))
    }

    @Test
    fun `returns null when no price seen for symbol`() {
        val trade = broker.execute(marketOrder(symbol = "BTCUSD"))
        assertThat(trade).isNull()
    }

    @Test
    fun `Trade has same orderId, symbol, qty, side as Order`() {
        tracker.update("XAUUSD", Money.of("2400.0"))
        val order = marketOrder(id = "ORD-7", side = Side.SELL, qty = Money.of("3.5"))
        val trade = broker.execute(order)
        assertThat(trade).isNotNull
        assertThat(trade!!.orderId).isEqualTo("ORD-7")
        assertThat(trade.symbol).isEqualTo("XAUUSD")
        assertThat(trade.quantity).isEqualByComparingTo(Money.of("3.5"))
        assertThat(trade.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `Trade timestamp is broker's clock-now`() {
        tracker.update("XAUUSD", Money.of("2400.0"))
        clock.time = 9999L
        val trade = broker.execute(marketOrder(ts = 1000L))
        assertThat(trade!!.timestamp).isEqualTo(9999L)
    }

    @Test
    fun `throws on LIMIT order with null price`() {
        val order = Order("ORD-0", "XAUUSD", Side.BUY, Money.of("1"), OrderType.LIMIT, null, 1000L)
        assertThatThrownBy { broker.execute(order) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `throws on STOP order with null price`() {
        val order = Order("ORD-0", "XAUUSD", Side.BUY, Money.of("1"), OrderType.STOP, null, 1000L)
        assertThatThrownBy { broker.execute(order) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `throws on order with non-positive quantity`() {
        tracker.update("XAUUSD", Money.of("2400.0"))
        assertThatThrownBy { broker.execute(marketOrder(qty = Money.of("0"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { broker.execute(marketOrder(qty = Money.of("-1"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `fills LIMIT at order price not tracker price`() {
        tracker.update("XAUUSD", Money.of("2400.0"))
        val order =
            Order(
                "ORD-0",
                "XAUUSD",
                Side.BUY,
                Money.of("1"),
                OrderType.LIMIT,
                price = Money.of("2350.0"),
                timestamp = 1000L,
            )
        val trade = broker.execute(order)
        assertThat(trade!!.price).isEqualByComparingTo(Money.of("2350.0"))
    }
}
