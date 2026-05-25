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

class MaxStrategyOpenPositionsTest {
    private val global = PositionTracker()
    private val strategyPositions = StrategyPositionTracker()
    private val rule = MaxStrategyOpenPositions("ema_cross", maxCount = 2, strategyPositions = strategyPositions)

    private fun order(
        strategyId: String = "ema_cross",
        symbol: String,
        qty: BigDecimal = Money.of("1"),
    ) = OrderRequest.Market(
        id = "ORD-0",
        symbol = symbol,
        side = Side.BUY,
        quantity = qty,
        timeInForce = TimeInForce.GTC,
        timestamp = 1000L,
        strategyId = strategyId,
    )

    private fun fill(
        strategyId: String,
        symbol: String,
    ) {
        strategyPositions.apply(
            strategyId,
            Trade(
                orderId = "ORD-X",
                symbol = symbol,
                price = Money.of("100"),
                quantity = Money.of("1"),
                side = Side.BUY,
                timestamp = 1000L,
            ),
        )
    }

    @Test
    fun `approves orders from other strategies`() {
        fill("ema_cross", "XAUUSD")
        fill("ema_cross", "EURUSD")
        val decision = rule.evaluate(order(strategyId = "rsi_mr", symbol = "SPX500"), global)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves add-to-existing-position regardless of count`() {
        fill("ema_cross", "XAUUSD")
        fill("ema_cross", "EURUSD") // at cap (2)
        // Same-symbol re-entry isn't a "new" position → should be approved.
        val decision = rule.evaluate(order(symbol = "XAUUSD"), global)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves new symbol when under cap`() {
        fill("ema_cross", "XAUUSD") // 1 open, cap is 2
        val decision = rule.evaluate(order(symbol = "EURUSD"), global)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `rejects new symbol that would push count past the cap`() {
        fill("ema_cross", "XAUUSD")
        fill("ema_cross", "EURUSD")
        val decision = rule.evaluate(order(symbol = "SPX500"), global)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason)
            .contains("MaxStrategyOpenPositions", "ema_cross", "2", "max 2")
    }

    @Test
    fun `count is per-strategy not global`() {
        fill("other_strat", "AAA")
        fill("other_strat", "BBB")
        fill("other_strat", "CCC") // 3 positions for other strat
        val decision = rule.evaluate(order(symbol = "XAUUSD"), global) // ema_cross has 0
        assertThat(decision).isEqualTo(Decision.Approve)
    }
}
