package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaxPositionSizeTest {
    private val positions = PositionTracker()
    private val rule = MaxPositionSize("XAUUSD", maxQty = Money.of("3"))

    private fun order(
        symbol: String = "XAUUSD",
        side: Side = Side.BUY,
        qty: BigDecimal = Money.of("1"),
    ) = Order("ORD-0", symbol, side, qty, OrderType.MARKET, null, 1000L)

    private fun fill(
        symbol: String,
        qty: BigDecimal,
        side: Side,
    ) {
        positions.apply(
            Trade(
                orderId = "ORD-X",
                symbol = symbol,
                price = Money.of("100"),
                quantity = qty,
                side = side,
                timestamp = 1000L,
            ),
        )
    }

    @Test
    fun `approves order for non-target symbol`() {
        fill("XAUUSD", Money.of("5"), Side.BUY)
        val decision = rule.evaluate(order(symbol = "EURUSD", qty = Money.of("100")), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order under the cap`() {
        fill("XAUUSD", Money.of("1"), Side.BUY)
        val decision = rule.evaluate(order(qty = Money.of("1")), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order at the cap`() {
        fill("XAUUSD", Money.of("2"), Side.BUY)
        val decision = rule.evaluate(order(qty = Money.of("1")), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `rejects order over the cap on the long side`() {
        fill("XAUUSD", Money.of("3"), Side.BUY)
        val decision = rule.evaluate(order(qty = Money.of("1")), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("MaxPositionSize", "4.0", "3.0", "XAUUSD")
    }

    @Test
    fun `rejects order over the cap on the short side`() {
        fill("XAUUSD", Money.of("3"), Side.SELL)
        val decision = rule.evaluate(order(side = Side.SELL, qty = Money.of("1")), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("MaxPositionSize")
    }
}
