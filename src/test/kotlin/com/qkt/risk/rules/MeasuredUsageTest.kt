package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.TestClock
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MeasuredUsageTest {
    private fun entry(qty: String) =
        OrderRequest.Market(
            id = "m1",
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = Money.of(qty),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    @Test
    fun `entries above the validation cap reject during the window and pass after`() {
        val clock = TestClock(0L)
        val rule = MeasuredUsage(clock, startedAtMs = 0L, windowHours = 24L, maxQty = BigDecimal("0.01"))
        val positions = PositionTracker()

        val decision = rule.evaluate(entry("0.5"), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("measured-usage")
        // Minimum-size entries trade through the window — that's the point.
        assertThat(rule.evaluate(entry("0.01"), positions)).isEqualTo(Decision.Approve)

        clock.t = 24L * 3_600_000L
        assertThat(rule.evaluate(entry("0.5"), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `closes pass at any size during the window`() {
        // A pre-existing 0.5 position must remain closable even though new 0.5 entries
        // reject — the window restricts new risk, not the way out.
        val clock = TestClock(0L)
        val positions = PositionTracker()
        positions.applyFill(
            BrokerEvent.OrderFilled(
                clientOrderId = "o1",
                brokerOrderId = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = Money.of("2000"),
                quantity = Money.of("0.5"),
                timestamp = 0L,
            ),
        )
        val rule = MeasuredUsage(clock, startedAtMs = 0L, windowHours = 24L)
        val close =
            OrderRequest.Market(
                id = "c1",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = Money.of("0.5"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        assertThat(rule.evaluate(close, positions)).isEqualTo(Decision.Approve)
    }
}
