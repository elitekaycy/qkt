package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The gate reports how long an open stale episode has been silent in session on every health check, so
 * alerting can escalate a feed that stays dead. A gap the venue planned is never counted: the count stops
 * in a scheduled break or out of session and restarts when the venue reopens, so a stale Friday followed
 * by a weekend does not page at Sunday's open.
 */
class MarketDataGateStillStaleTest {
    private class TickingClock(
        var t: Long = 0L,
    ) : MutableClock {
        override fun now(): Long = t

        override fun advanceTo(timestamp: Long) {
            t = timestamp
        }
    }

    private val ages = mutableListOf<Long>()

    private fun gate(
        clock: TickingClock,
        open: () -> Boolean = { true },
        paused: () -> Boolean = { false },
    ) = MarketDataGate(
        clock,
        minStaleAgeMs = 1_000L,
        inSession = { _, _ -> open() },
        scheduledBreak = { _, _ -> paused() },
        onStillStale = { _, ms -> ages.add(ms) },
    )

    @Test
    fun `an open stale episode reports its age on each check in session`() {
        val clock = TickingClock(1L)
        val gate = gate(clock)
        gate.observe(Tick("X", Money.of("100"), clock.t))
        clock.t += 2_000L
        assertThat(gate.isHealthy("X")).isFalse()
        clock.t += 60_000L
        assertThat(gate.isHealthy("X")).isFalse()

        assertThat(ages).containsExactly(0L, 60_000L)
    }

    @Test
    fun `a stale episode that runs into a scheduled break restarts its count when the break ends`() {
        val clock = TickingClock(1L)
        var paused = false
        val gate = gate(clock, paused = { paused })
        gate.observe(Tick("X", Money.of("100"), clock.t))
        clock.t += 2_000L
        gate.isHealthy("X")
        paused = true
        clock.t += 600_000L
        gate.isHealthy("X")
        paused = false
        clock.t += 1_000L
        gate.isHealthy("X")
        clock.t += 90_000L
        gate.isHealthy("X")

        assertThat(ages).containsExactly(0L, 0L, 90_000L)
    }

    @Test
    fun `a stale Friday that runs into the weekend counts from the reopen`() {
        val clock = TickingClock(1L)
        var open = true
        val gate = gate(clock, open = { open })
        gate.observe(Tick("X", Money.of("100"), clock.t))
        clock.t += 2_000L
        gate.isHealthy("X")
        open = false
        clock.t += 172_800_000L
        gate.isHealthy("X")
        open = true
        clock.t += 1_000L
        gate.isHealthy("X")

        assertThat(ages).containsExactly(0L, 0L)
    }

    @Test
    fun `a fresh tick ends the reports`() {
        val clock = TickingClock(1L)
        val gate = gate(clock)
        gate.observe(Tick("X", Money.of("100"), clock.t))
        clock.t += 2_000L
        gate.isHealthy("X")
        gate.observe(Tick("X", Money.of("100"), clock.t))
        clock.t += 100L
        assertThat(gate.isHealthy("X")).isTrue()

        assertThat(ages).containsExactly(0L)
    }
}
