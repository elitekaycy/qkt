package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.MutableClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketDataHealthRuleTest {
    private class TickingClock(
        var t: Long = 0L,
    ) : MutableClock {
        override fun now(): Long = t

        override fun advanceTo(timestamp: Long) {
            t = timestamp
        }
    }

    private fun tick(
        price: String,
        ts: Long,
        bid: String? = null,
        ask: String? = null,
    ) = Tick("X", Money.of(price), ts, bid = bid?.let(Money::of), ask = ask?.let(Money::of))

    @Test
    fun `stale data blocks entries but permits exits and recovers automatically`() {
        val clock = TickingClock(1L)
        val gate = MarketDataGate(clock, minStaleAgeMs = 1_000L)
        val rule = MarketDataHealthRule(gate)
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val entry = marketOrder("entry", Side.BUY)
        val exit = marketOrder("exit", Side.SELL, closesTicket = "42")

        gate.observe(tick("100", clock.t))
        clock.t += 2_000L

        assertThat(rule.evaluate(entry, positions)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(exit, positions)).isEqualTo(Decision.Approve)

        gate.observe(tick("100", clock.t))
        assertThat(rule.evaluate(entry, positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `stale data blocks reentry but not exits and recovery reopens the gate`() {
        val clock = TickingClock(1L)
        val gate = MarketDataGate(clock, minStaleAgeMs = 1_000L)
        val rule = MarketDataHealthRule(gate)
        val flatPositions = StrategyPositionTracker().account
        val firstEntry = marketOrder("first-entry", Side.BUY)
        val secondEntry = marketOrder("second-entry", Side.BUY)
        val protectiveExit = marketOrder("protective-exit", Side.SELL, closesTicket = "open-ticket")

        gate.observe(tick("100", clock.t))
        assertThat(rule.evaluate(firstEntry, flatPositions)).isEqualTo(Decision.Approve)

        clock.t += 2_000L
        assertThat(rule.evaluate(secondEntry, flatPositions)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(protectiveExit, flatPositions)).isEqualTo(Decision.Approve)

        gate.observe(tick("100", clock.t))
        assertThat(rule.evaluate(secondEntry, flatPositions)).isEqualTo(Decision.Approve)
    }

    private fun marketOrder(
        id: String,
        side: Side,
        closesTicket: String? = null,
    ): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = "X",
            side = side,
            quantity = BigDecimal.ONE,
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            closesTicket = closesTicket,
        )
}
