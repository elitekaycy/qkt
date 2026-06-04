package com.qkt.chaos

import com.qkt.common.net.ExponentialBackoff
import com.qkt.common.net.ReconnectSupervisor
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconnectChaosTest {
    @Test
    fun `recovers after two failed attempts and fires onReconnected exactly once`() {
        val sched = SyncScheduler()
        val attempts = AtomicInteger(0)
        val reconnected = AtomicInteger(0)
        val supervisor =
            ReconnectSupervisor(
                backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L),
                attemptReconnect = { attempts.incrementAndGet() >= 3 }, // false, false, true
                onReconnected = { reconnected.incrementAndGet() },
                executor = sched.asExecutor(),
            )

        supervisor.scheduleReconnect()
        assertThat(supervisor.isReconnecting).isTrue()
        sched.runNext() // attempt 1 -> false -> reschedules
        sched.runNext() // attempt 2 -> false -> reschedules
        sched.runNext() // attempt 3 -> true  -> onReconnected

        assertThat(attempts.get()).isEqualTo(3)
        assertThat(reconnected.get()).isEqualTo(1)
        assertThat(supervisor.isReconnecting).isFalse()
        assertThat(sched.scheduledDelays).containsExactly(1_000L, 2_000L, 4_000L)
    }
}
