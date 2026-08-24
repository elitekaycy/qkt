package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.MutableClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketDataGateTest {
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
    fun `stale data suppresses health and resumes on fresh ticks`() {
        val clock = TickingClock(0L)
        val gate = MarketDataGate(clock, minStaleAgeMs = 1_000L)
        // Steady 100ms cadence establishes the gap baseline.
        repeat(20) {
            clock.t += 100L
            gate.observe(tick("100", clock.t))
        }
        assertThat(gate.isHealthy("X")).isTrue()

        // Feed freezes: 30s with no ticks — well past 5x the 100ms gap and the 1s floor.
        clock.t += 30_000L
        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(gate.staleSymbols()).containsKey("X")

        // Data resumes — health auto-recovers, no operator action needed.
        gate.observe(tick("100", clock.t))
        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(gate.staleSymbols()).isEmpty()
    }

    @Test
    fun `stale transition invokes the operator alert once`() {
        val clock = TickingClock(0L)
        val alerts = mutableListOf<String>()
        val gate =
            MarketDataGate(
                clock,
                minStaleAgeMs = 1_000L,
                onUnhealthy = { symbol, reason -> alerts.add("$symbol:$reason") },
            )
        clock.t = 1L
        gate.observe(tick("100", clock.t))
        clock.t += 2_000L

        repeat(3) { assertThat(gate.isHealthy("X")).isFalse() }

        assertThat(alerts).hasSize(1)
        assertThat(alerts.single()).contains("X:quote age")
    }

    @Test
    fun `an implausible outlier tick is rejected, plausible moves pass`() {
        val clock = TickingClock(0L)
        val gate = MarketDataGate(clock)
        // Window of prices oscillating around 100.
        repeat(32) { i ->
            clock.t += 100L
            gate.observe(tick(if (i % 2 == 0) "100.0" else "100.2", clock.t))
        }
        clock.t += 100L
        // 100 -> 250 is not a move, it's a glitch.
        assertThat(gate.observe(tick("250", clock.t))).isEqualTo(MarketDataGate.Verdict.OUTLIER)
        assertThat(gate.outlierCount.get()).isEqualTo(1L)
        clock.t += 100L
        assertThat(gate.observe(tick("100.4", clock.t))).isEqualTo(MarketDataGate.Verdict.OK)
    }

    @Test
    fun `a crossed book is treated as an outlier`() {
        val clock = TickingClock(0L)
        val gate = MarketDataGate(clock)
        clock.t += 100L
        val verdict = gate.observe(tick("100", clock.t, bid = "100.5", ask = "99.5"))
        assertThat(verdict).isEqualTo(MarketDataGate.Verdict.OUTLIER)
    }

    @Test
    fun `a coherent price gap re-baselines instead of freezing the symbol`() {
        val clock = TickingClock(0L)
        val gate = MarketDataGate(clock)
        repeat(32) {
            clock.t += 100L
            gate.observe(tick("100", clock.t))
        }

        repeat(2) {
            clock.t += 100L
            assertThat(gate.observe(tick("98", clock.t))).isEqualTo(MarketDataGate.Verdict.OUTLIER)
            assertThat(gate.isHealthy("X")).isFalse()
        }
        clock.t += 100L
        assertThat(gate.observe(tick("98", clock.t))).isEqualTo(MarketDataGate.Verdict.OK)
        assertThat(gate.isHealthy("X")).isTrue()

        clock.t += 100L
        assertThat(gate.observe(tick("98.1", clock.t))).isEqualTo(MarketDataGate.Verdict.OK)
        assertThat(gate.outlierCount.get()).isEqualTo(2L)
    }

    @Test
    fun `crossed books never trigger a price re-baseline`() {
        val clock = TickingClock(0L)
        val gate = MarketDataGate(clock)

        repeat(4) {
            clock.t += 100L
            assertThat(gate.observe(tick("100", clock.t, bid = "101", ask = "99")))
                .isEqualTo(MarketDataGate.Verdict.OUTLIER)
        }
        assertThat(gate.isHealthy("X")).isFalse()
    }

    @Test
    fun `never-observed symbols are healthy`() {
        val gate = MarketDataGate(TickingClock(0L))
        assertThat(gate.isHealthy("NEVER_SEEN")).isTrue()
    }

    @Test
    fun `stale data blocks entries but permits exits and recovers automatically`() {
        val clock = TickingClock(1L)
        val gate = MarketDataGate(clock, minStaleAgeMs = 1_000L)
        val rule = MarketDataHealthRule(gate)
        val positions = PositionTracker()
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
        val flatPositions = PositionTracker()
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

    @Test
    fun `a print older than any server zone offset is a venue gap, not clock skew`() {
        // Sunday 23:02Z daemon start: copper's newest print is Friday's close, 53h old.
        val clock = TickingClock(0L)
        val alerts = mutableListOf<String>()
        val gate = MarketDataGate(clock, onUnhealthy = { symbol, reason -> alerts.add("$symbol:$reason") })
        clock.t = 53L * 3_600_000L
        gate.observe(tick("4.10", ts = 1L))

        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(alerts).isEmpty()
        assertThat(gate.clockSkewedSymbols()).containsKey("X")

        // First fresh Monday print clears it without operator action.
        clock.t += 60_000L
        gate.observe(tick("4.11", ts = clock.t))
        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(alerts).isEmpty()
    }

    @Test
    fun `a print trailing the clock while the venue is closed is a venue gap`() {
        // Saturday: the calendar says closed, the last print is 2h old — plausible as an
        // offset on its own, but the venue is shut, so nothing is skewed.
        val clock = TickingClock(0L)
        val alerts = mutableListOf<String>()
        val gate =
            MarketDataGate(
                clock,
                onUnhealthy = { symbol, reason -> alerts.add("$symbol:$reason") },
                inSession = { _, _ -> false },
            )
        clock.t = 2L * 3_600_000L
        gate.observe(tick("100", ts = 1L))

        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(alerts).isEmpty()
    }

    @Test
    fun `a plausible offset during the session is still reported as clock skew`() {
        // Wednesday, venue open, prints 3h behind the local clock: a mis-set server zone.
        val clock = TickingClock(0L)
        val alerts = mutableListOf<String>()
        val gate = MarketDataGate(clock, onUnhealthy = { symbol, reason -> alerts.add("$symbol:$reason") })
        clock.t = 3L * 3_600_000L
        gate.observe(tick("100", ts = 1L))

        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(alerts).singleElement().asString().contains("clock skew")
    }

    @Test
    fun `a print ahead of the local clock is clock skew even when the venue is closed`() {
        // Broker time in the future can never be a stale print.
        val clock = TickingClock(0L)
        val alerts = mutableListOf<String>()
        val gate =
            MarketDataGate(
                clock,
                onUnhealthy = { symbol, reason -> alerts.add("$symbol:$reason") },
                inSession = { _, _ -> false },
            )
        clock.t = 1_000L
        gate.observe(tick("100", ts = clock.t + 3L * 3_600_000L))

        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(alerts).singleElement().asString().contains("clock skew")
    }
}
