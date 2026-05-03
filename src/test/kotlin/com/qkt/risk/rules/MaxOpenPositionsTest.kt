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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MaxOpenPositionsTest {
    private val positions = PositionTracker()

    private fun order(
        symbol: String,
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
    fun `approves order when below the limit`() {
        val rule = MaxOpenPositions(maxCount = 2)
        fill("XAUUSD", Money.of("1"), Side.BUY)
        val decision = rule.evaluate(order("EURUSD"), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order for symbol already held even at the limit`() {
        val rule = MaxOpenPositions(maxCount = 2)
        fill("XAUUSD", Money.of("1"), Side.BUY)
        fill("EURUSD", Money.of("5"), Side.BUY)
        val decision = rule.evaluate(order("XAUUSD"), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `rejects new opening when at the limit`() {
        val rule = MaxOpenPositions(maxCount = 2)
        fill("XAUUSD", Money.of("1"), Side.BUY)
        fill("EURUSD", Money.of("5"), Side.BUY)
        val decision = rule.evaluate(order("GBPUSD"), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("MaxOpenPositions", "2")
    }

    @Test
    fun `approves new opening when previous was closed`() {
        val rule = MaxOpenPositions(maxCount = 1)
        fill("XAUUSD", Money.of("1"), Side.BUY)
        fill("XAUUSD", Money.of("1"), Side.SELL)
        val decision = rule.evaluate(order("EURUSD"), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `throws on non-positive maxCount`() {
        assertThatThrownBy { MaxOpenPositions(maxCount = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MaxOpenPositions(maxCount = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
