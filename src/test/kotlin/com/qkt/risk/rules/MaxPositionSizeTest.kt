package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaxPositionSizeTest {
    private val positions = PositionTracker()
    private val rule = MaxPositionSize("XAUUSD", maxQty = 3.0)

    private fun order(
        symbol: String = "XAUUSD",
        side: Side = Side.BUY,
        qty: Double = 1.0,
    ) = Order("ORD-0", symbol, side, qty, OrderType.MARKET, null, 1000L)

    private fun fill(
        symbol: String,
        qty: Double,
        side: Side,
    ) {
        positions.apply(
            Trade(orderId = "ORD-X", symbol = symbol, price = 100.0, quantity = qty, side = side, timestamp = 1000L),
        )
    }

    @Test
    fun `approves order for non-target symbol`() {
        fill("XAUUSD", 5.0, Side.BUY)
        val decision = rule.evaluate(order(symbol = "EURUSD", qty = 100.0), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order under the cap`() {
        fill("XAUUSD", 1.0, Side.BUY)
        val decision = rule.evaluate(order(qty = 1.0), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order at the cap`() {
        fill("XAUUSD", 2.0, Side.BUY)
        val decision = rule.evaluate(order(qty = 1.0), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `rejects order over the cap on the long side`() {
        fill("XAUUSD", 3.0, Side.BUY)
        val decision = rule.evaluate(order(qty = 1.0), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("MaxPositionSize", "4.0", "3.0", "XAUUSD")
    }

    @Test
    fun `rejects order over the cap on the short side`() {
        fill("XAUUSD", 3.0, Side.SELL)
        val decision = rule.evaluate(order(side = Side.SELL, qty = 1.0), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("MaxPositionSize")
    }
}
