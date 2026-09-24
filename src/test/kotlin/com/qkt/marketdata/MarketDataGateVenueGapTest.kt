package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketDataGateVenueGapTest {
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
    fun `a print older than any server zone offset is a venue gap, not clock skew`() {
        // Sunday 23:02Z daemon start: copper's newest print is Friday's close, 53h old.
        val clock = TickingClock(0L)
        val alerts = mutableListOf<String>()
        val gate = MarketDataGate(clock, onUnhealthy = { symbol, reason, _ -> alerts.add("$symbol:$reason") })
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
                onUnhealthy = { symbol, reason, _ -> alerts.add("$symbol:$reason") },
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
        val gate = MarketDataGate(clock, onUnhealthy = { symbol, reason, _ -> alerts.add("$symbol:$reason") })
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
                onUnhealthy = { symbol, reason, _ -> alerts.add("$symbol:$reason") },
                inSession = { _, _ -> false },
            )
        clock.t = 1_000L
        gate.observe(tick("100", ts = clock.t + 3L * 3_600_000L))

        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(alerts).singleElement().asString().contains("clock skew")
    }
}
