package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaxStrategyPositionSizeTest {
    private val global = PositionTracker()
    private val strategyPositions = StrategyPositionTracker()
    private val rule = MaxStrategyPositionSize("ema_cross", maxQty = Money.of("3"), strategyPositions = strategyPositions)

    private fun order(
        strategyId: String = "ema_cross",
        symbol: String = "XAUUSD",
        side: Side = Side.BUY,
        qty: BigDecimal = Money.of("1"),
    ) = OrderRequest.Market(
        id = "ORD-0",
        symbol = symbol,
        side = side,
        quantity = qty,
        timeInForce = TimeInForce.GTC,
        timestamp = 1000L,
        strategyId = strategyId,
    )

    private fun fill(
        strategyId: String,
        symbol: String,
        qty: BigDecimal,
        side: Side,
    ) {
        strategyPositions.apply(
            strategyId,
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
    fun `approves orders from other strategies`() {
        fill("ema_cross", "XAUUSD", Money.of("3"), Side.BUY)
        val decision = rule.evaluate(order(strategyId = "rsi_mean_reversion"), global)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order under the cap`() {
        fill("ema_cross", "XAUUSD", Money.of("1"), Side.BUY)
        val decision = rule.evaluate(order(qty = Money.of("1")), global)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order at the cap`() {
        fill("ema_cross", "XAUUSD", Money.of("2"), Side.BUY)
        val decision = rule.evaluate(order(qty = Money.of("1")), global)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `rejects order that would push position over the cap`() {
        fill("ema_cross", "XAUUSD", Money.of("3"), Side.BUY)
        val decision = rule.evaluate(order(qty = Money.of("1")), global)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason)
            .contains("MaxStrategyPositionSize", "ema_cross", "XAUUSD")
    }

    @Test
    fun `isolates from other strategies on the same symbol`() {
        // Another strategy is fully loaded on XAUUSD. ema_cross is flat. ema_cross order should approve.
        fill("other_strat", "XAUUSD", Money.of("10"), Side.BUY)
        val decision = rule.evaluate(order(qty = Money.of("1")), global)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `short side respects the cap symmetrically`() {
        fill("ema_cross", "XAUUSD", Money.of("3"), Side.SELL)
        val decision = rule.evaluate(order(side = Side.SELL, qty = Money.of("1")), global)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
    }
}
