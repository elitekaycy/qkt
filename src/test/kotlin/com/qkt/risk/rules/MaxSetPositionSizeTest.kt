package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MaxSetPositionSizeTest {
    private val global = StrategyPositionTracker().account
    private val strategyPositions = StrategyPositionTracker()
    private val stocks = setOf("ALPACA:AAPL", "ALPACA:MSFT")
    private val rule =
        MaxSetPositionSize("rotation", "stocks", stocks, maxQty = Money.of("3"), strategyPositions = strategyPositions)

    private fun order(
        symbol: String = "ALPACA:AAPL",
        side: Side = Side.BUY,
        qty: BigDecimal = Money.of("1"),
        strategyId: String = "rotation",
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
        symbol: String,
        qty: BigDecimal,
        side: Side,
    ) {
        strategyPositions.apply(
            "rotation",
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

    private fun withPending(qty: BigDecimal) =
        object : com.qkt.positions.PositionProvider by global {
            override fun pendingOrderQuantity(
                symbol: String,
                side: Side,
                strategyId: String?,
            ): BigDecimal = qty
        }

    @Test
    fun `members up to the cap pass and other symbols or strategies are untouched`() {
        fill("ALPACA:AAPL", Money.of("2"), Side.BUY)
        assertThat(rule.evaluate(order(), global)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order(qty = Money.of("2"), strategyId = "other"), global)).isEqualTo(Decision.Approve)
        assertThat(
            rule.evaluate(order(symbol = "EXNESS:XAUUSD", qty = Money.of("50")), global),
        ).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a member's projected position past the cap is rejected, pending included, on either side`() {
        fill("ALPACA:AAPL", Money.of("2"), Side.BUY)
        val rejected = rule.evaluate(order(qty = Money.of("2")), global)
        assertThat((rejected as Decision.Reject).reason)
            .startsWith("MaxSetPositionSize[rotation/stocks]: |4.00000000| > 3.00000000 for ALPACA:AAPL (pending=")
        assertThat(rule.evaluate(order(), withPending(Money.of("1")))).isInstanceOf(Decision.Reject::class.java)
        fill("ALPACA:MSFT", Money.of("2"), Side.SELL)
        assertThat(rule.evaluate(order(symbol = "ALPACA:MSFT", side = Side.SELL), global)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order(symbol = "ALPACA:MSFT", side = Side.SELL, qty = Money.of("2")), global))
            .isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `construction validates its arguments`() {
        assertThatThrownBy {
            MaxSetPositionSize("", "s", stocks, Money.of("1"), strategyPositions)
        }.hasMessage("strategyId must be non-blank")
        assertThatThrownBy {
            MaxSetPositionSize("r", "s", emptySet(), Money.of("1"), strategyPositions)
        }.hasMessage("set 's' has no members")
        assertThatThrownBy {
            MaxSetPositionSize("r", "s", stocks, BigDecimal.ZERO, strategyPositions)
        }.hasMessage("maxQty must be > 0: 0")
    }
}
