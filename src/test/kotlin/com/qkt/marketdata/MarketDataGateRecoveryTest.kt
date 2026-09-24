package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketDataGateRecoveryTest {
    private class TickingClock(
        var t: Long = 0L,
    ) : MutableClock {
        override fun now(): Long = t

        override fun advanceTo(timestamp: Long) {
            t = timestamp
        }
    }

    private data class Recovery(
        val symbol: String,
        val reason: String,
        val unhealthyForMs: Long,
    )

    private val faults = mutableListOf<FeedFault>()
    private val recoveries = mutableListOf<Recovery>()

    private fun gate(
        clock: TickingClock,
        paused: () -> Boolean = { false },
    ) = MarketDataGate(
        clock,
        minStaleAgeMs = 1_000L,
        onUnhealthy = { _, _, fault -> faults.add(fault) },
        onRecovered = { symbol, reason, ms -> recoveries.add(Recovery(symbol, reason, ms)) },
        scheduledBreak = { _, _ -> paused() },
    )

    private fun tick(
        price: String,
        ts: Long,
    ) = Tick("X", Money.of(price), ts)

    @Test
    fun `an in-session stale episode recovers once with how long it lasted`() {
        val clock = TickingClock(1L)
        val gate = gate(clock)
        gate.observe(tick("100", clock.t))
        clock.t += 2_000L
        repeat(3) { assertThat(gate.isHealthy("X")).isFalse() }
        assertThat(faults).containsExactly(FeedFault.STALE)

        clock.t += 58_000L
        gate.observe(tick("100", clock.t))
        clock.t += 100L
        gate.observe(tick("100", clock.t))

        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(recoveries).containsExactly(Recovery("X", "fresh tick after stale", 58_000L))
    }

    @Test
    fun `a clock-skew episode reports its kind and recovers when the clock realigns`() {
        val clock = TickingClock(100_000_000L)
        val gate = gate(clock)
        gate.observe(tick("100", clock.t - 3L * 3_600_000L))
        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(faults).containsExactly(FeedFault.CLOCK_SKEW)

        // Still skewed: the latch holds, no recovery yet.
        clock.t += 500L
        gate.observe(tick("100", clock.t - 3L * 3_600_000L))
        assertThat(recoveries).isEmpty()

        clock.t += 1_500L
        gate.observe(tick("100", clock.t))
        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(recoveries).containsExactly(Recovery("X", "fresh tick after clock_skew", 2_000L))
    }

    @Test
    fun `a symbol that raised stale and clock skew recovers once, when both have cleared`() {
        val clock = TickingClock(100_000_000L)
        val gate = gate(clock)
        gate.observe(tick("100", clock.t))
        clock.t += 2_000L
        assertThat(gate.isHealthy("X")).isFalse()

        // The feed comes back on a skewed clock: stale clears, skew opens, same episode.
        clock.t += 1_000L
        gate.observe(tick("100", clock.t - 3L * 3_600_000L))
        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(faults).containsExactly(FeedFault.STALE, FeedFault.CLOCK_SKEW)
        assertThat(recoveries).isEmpty()

        clock.t += 4_000L
        gate.observe(tick("100", clock.t))
        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(recoveries).containsExactly(Recovery("X", "fresh tick after stale", 5_000L))
    }

    @Test
    fun `an outlier episode recovers on the next plausible tick`() {
        val clock = TickingClock(0L)
        val gate = gate(clock)
        repeat(20) {
            clock.t += 100L
            gate.observe(tick("100", clock.t))
        }
        clock.t += 100L
        assertThat(gate.observe(tick("500", clock.t))).isEqualTo(MarketDataGate.Verdict.OUTLIER)
        assertThat(gate.isHealthy("X")).isFalse()
        assertThat(faults).containsExactly(FeedFault.OUTLIER)

        clock.t += 300L
        gate.observe(tick("100", clock.t))
        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(recoveries).containsExactly(Recovery("X", "fresh tick after outlier", 300L))
    }

    @Test
    fun `a scheduled pause neither alerts nor recovers`() {
        val clock = TickingClock(1L)
        var paused = false
        val gate = gate(clock) { paused }
        gate.observe(tick("100", clock.t))
        paused = true
        clock.t += 2_000L
        assertThat(gate.isHealthy("X")).isFalse()

        paused = false
        clock.t += 100L
        gate.observe(tick("100", clock.t))
        assertThat(gate.isHealthy("X")).isTrue()
        assertThat(faults).isEmpty()
        assertThat(recoveries).isEmpty()
    }
}
