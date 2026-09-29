package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The end of a scheduled venue break (#1279): the venue's first post-break print lags the
 * calendar's break end by seconds to minutes, so the gap is measured from the break end,
 * not from the last pre-break print.
 */
class MarketDataGateBreakEndTest {
    private class TickingClock(
        var t: Long = 0L,
    ) : MutableClock {
        override fun now(): Long = t

        override fun advanceTo(timestamp: Long) {
            t = timestamp
        }
    }

    private class Rig {
        val clock = TickingClock(0L)
        val alerts = mutableListOf<String>()
        var paused = false
        val gate =
            MarketDataGate(
                clock,
                minStaleAgeMs = 1_000L,
                onUnhealthy = { symbol, reason, _ -> alerts.add("$symbol:$reason") },
                scheduledBreak = { _, _ -> paused },
            )

        fun tick(
            price: String,
            ts: Long,
        ) = Tick("X", Money.of(price), ts)

        /** Last print at t=1, then the break starts and the gate observes the pause. */
        fun intoBreak() {
            clock.t = 1L
            gate.observe(tick("100", clock.t))
            paused = true
            clock.t += 2_000L
            assertThat(gate.isHealthy("X")).isFalse()
            assertThat(alerts).isEmpty()
        }
    }

    @Test
    fun `the heartbeats right after a break ends are still a pause, not a stale fault`() {
        val rig = Rig()
        rig.intoBreak()

        // Break over on the calendar; the venue has not printed yet. Well inside the threshold.
        rig.paused = false
        repeat(5) {
            rig.clock.t += 100L
            assertThat(rig.gate.isHealthy("X")).isFalse()
        }
        assertThat(rig.alerts).isEmpty()

        rig.gate.observe(rig.tick("100", rig.clock.t))
        assertThat(rig.gate.isHealthy("X")).isTrue()
        assertThat(rig.alerts).isEmpty()
    }

    @Test
    fun `a gap outliving the break by more than the threshold alerts once, aged from the break end`() {
        val rig = Rig()
        rig.intoBreak()

        rig.paused = false
        rig.clock.t += 1_500L
        assertThat(rig.gate.isHealthy("X")).isFalse()
        assertThat(rig.gate.isHealthy("X")).isFalse()

        assertThat(rig.alerts).hasSize(1)
        assertThat(rig.alerts.single())
            .contains("after the scheduled break ended")
            .contains("1500ms")
            .doesNotContain("3500ms")
    }

    @Test
    fun `a pause resumed by a fresh tick forgets the break for the next stale judgment`() {
        val rig = Rig()
        rig.intoBreak()

        rig.paused = false
        rig.clock.t += 100L
        rig.gate.observe(rig.tick("100", rig.clock.t))
        assertThat(rig.gate.isHealthy("X")).isTrue()

        // A later real freeze is judged from the last print, as before.
        rig.clock.t += 1_500L
        assertThat(rig.gate.isHealthy("X")).isFalse()
        assertThat(rig.alerts).singleElement().asString().contains("quote age 1500ms")
    }
}
