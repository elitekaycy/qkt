package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketDataGateClockSkewTest {
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
    ) = Tick("X", Money.of(price), ts)

    private val threeHoursMs = 10_800_000L

    @Test
    fun `broker clock skew beyond tolerance suppresses health and alerts once`() {
        val clock = TickingClock(1_784_000_000_000L)
        val alerts = mutableListOf<String>()
        val gate = MarketDataGate(clock, onUnhealthy = { _, reason -> alerts.add(reason) })
        // Feed timestamps sit 3 hours behind the local clock — a wrong server_time_zone,
        // not latency. Data keeps flowing, so the staleness check alone never fires.
        repeat(3) {
            clock.t += 100L
            gate.observe(tick("100", clock.t - threeHoursMs))
        }
        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(alerts).hasSize(1)
        assertThat(alerts.single()).contains("clock skew")
        assertThat(gate.clockSkewedSymbols()).containsEntry("X", -threeHoursMs)
    }

    @Test
    fun `clock realignment restores health automatically`() {
        val clock = TickingClock(1_784_000_000_000L)
        val gate = MarketDataGate(clock)
        gate.observe(tick("100", clock.t - threeHoursMs))
        assertThat(gate.isHealthy("X")).isFalse()

        // The operator fixes server_time_zone and timestamps line up again.
        clock.t += 100L
        gate.observe(tick("100", clock.t))
        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(gate.clockSkewedSymbols()).isEmpty()
    }

    @Test
    fun `honest feed latency stays healthy`() {
        val clock = TickingClock(1_784_000_000_000L)
        val gate = MarketDataGate(clock)
        // A sparse symbol's tick arriving 15s after its broker stamp is latency, not skew.
        gate.observe(tick("100", clock.t - 15_000L))
        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(gate.clockSkewedSymbols()).isEmpty()
    }
}
