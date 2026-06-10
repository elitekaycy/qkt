package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.MutableClock
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
    fun `never-observed symbols are healthy`() {
        val gate = MarketDataGate(TickingClock(0L))
        assertThat(gate.isHealthy("NEVER_SEEN")).isTrue()
    }
}
