package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MaxSetOpenPositionsTest {
    private val global = StrategyPositionTracker().account
    private val strategyPositions = StrategyPositionTracker()
    private val stocks = setOf("ALPACA:AAPL", "ALPACA:MSFT", "ALPACA:NVDA")
    private val rule =
        MaxSetOpenPositions("rotation", "stocks", stocks, maxCount = 2, strategyPositions = strategyPositions)

    private fun order(
        symbol: String,
        strategyId: String = "rotation",
    ) = OrderRequest.Market(
        id = "ORD-0",
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        timeInForce = TimeInForce.GTC,
        timestamp = 1000L,
        strategyId = strategyId,
    )

    private fun fill(
        symbol: String,
        strategyId: String = "rotation",
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

    private fun withInFlight(vararg symbols: String) =
        object : com.qkt.positions.PositionProvider by global {
            override fun pendingEntrySymbols(strategyId: String?): Set<String> = symbols.toSet()
        }

    @Test
    fun `members outside the cap, other strategies and symbols outside the set pass`() {
        fill("ALPACA:AAPL")
        assertThat(rule.evaluate(order("ALPACA:MSFT"), global)).isEqualTo(Decision.Approve)
        fill("ALPACA:MSFT")
        assertThat(rule.evaluate(order("ALPACA:NVDA", strategyId = "other"), global)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("EXNESS:XAUUSD"), global)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("ALPACA:AAPL"), global)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `the cap counts only the set's members, held or in flight`() {
        fill("EXNESS:XAUUSD")
        fill("EXNESS:EURUSD")
        fill("ALPACA:AAPL")
        assertThat(rule.evaluate(order("ALPACA:MSFT"), global)).isEqualTo(Decision.Approve)
        fill("ALPACA:MSFT")
        val rejected = rule.evaluate(order("ALPACA:NVDA"), global)
        assertThat(
            (rejected as Decision.Reject).reason,
        ).isEqualTo("MaxSetOpenPositions[rotation/stocks]: 2 already open, max 2")
        val burst = rule.evaluate(order("ALPACA:NVDA"), withInFlight("ALPACA:AAPL", "ALPACA:MSFT", "EXNESS:GBPUSD"))
        assertThat(burst).isInstanceOf(Decision.Reject::class.java)
        assertThat(
            rule.evaluate(order("ALPACA:MSFT"), withInFlight("ALPACA:AAPL", "ALPACA:MSFT")),
        ).isEqualTo(Decision.Approve)
    }

    @Test
    fun `an in-flight member counts once alongside its own fill`() {
        fill("ALPACA:AAPL")
        assertThat(rule.evaluate(order("ALPACA:MSFT"), withInFlight("ALPACA:AAPL"))).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a set whose members move is capped on whoever is a member at each check`() {
        var members = setOf("ALPACA:AAPL", "ALPACA:MSFT")
        val moving = MaxSetOpenPositions("rotation", "stocks", { members }, 1, strategyPositions)
        fill("ALPACA:AAPL")
        assertThat(moving.evaluate(order("ALPACA:MSFT"), global)).isInstanceOf(Decision.Reject::class.java)
        // NVDA joins: the cap now guards it too
        members = members + "ALPACA:NVDA"
        assertThat(moving.evaluate(order("ALPACA:NVDA"), global)).isInstanceOf(Decision.Reject::class.java)
        // AAPL leaves the set while still held: it no longer counts toward the set's cap
        members = setOf("ALPACA:MSFT", "ALPACA:NVDA")
        assertThat(moving.evaluate(order("ALPACA:MSFT"), global)).isEqualTo(Decision.Approve)
        // a member that has left is no longer capped at all
        members = setOf("ALPACA:NVDA")
        fill("ALPACA:NVDA")
        assertThat(moving.evaluate(order("ALPACA:MSFT"), global)).isEqualTo(Decision.Approve)
        // an emptied set caps nothing, and construction with a supplier does not need a member yet
        members = emptySet()
        assertThat(moving.evaluate(order("ALPACA:NVDA"), global)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `construction validates its arguments`() {
        assertThatThrownBy {
            MaxSetOpenPositions("", "s", stocks, 1, strategyPositions)
        }.hasMessage("strategyId must be non-blank")
        assertThatThrownBy {
            MaxSetOpenPositions("r", "s", emptySet(), 1, strategyPositions)
        }.hasMessage("set 's' has no members")
        assertThatThrownBy {
            MaxSetOpenPositions("", "s", { stocks }, 1, strategyPositions)
        }.hasMessage("strategyId must be non-blank")
        assertThatThrownBy {
            MaxSetOpenPositions(
                "r",
                "s",
                stocks,
                0,
                strategyPositions,
            )
        }.hasMessage("maxCount must be > 0: 0")
    }
}
