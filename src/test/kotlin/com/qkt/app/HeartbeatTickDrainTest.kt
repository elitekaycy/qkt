package com.qkt.app

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.util.concurrent.ArrayBlockingQueue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A heartbeat processes the ticks queued ahead of it first (#1058), but a feed that keeps the queue full must not hold
 * it, and every control message behind it, for as long as the feed keeps up (#1609).
 */
class HeartbeatTickDrainTest {
    private val nowMs = 1_000_000L

    private fun tick(atMs: Long) = Inbound.FeedTick(Tick("X:Y", BigDecimal.ONE, atMs))

    private fun queueOf(vararg stamps: Long) =
        ArrayBlockingQueue<Inbound.FeedTick>(100).apply { stamps.forEach { add(tick(it)) } }

    @Test
    fun `a feed that refills the queue as fast as it drains does not hold the heartbeat`() {
        val queue = queueOf(nowMs - 30, nowMs - 20, nowMs - 10)
        var arrived = 0

        val processed =
            drainTicksAheadOf(queue, nowMs) {
                if (arrived++ < 1_000) queue.add(tick(nowMs + arrived))
            }

        assertThat(processed).isEqualTo(3)
        assertThat(queue).isNotEmpty()
    }

    @Test
    fun `a tick that arrives during the drain stamped before the heartbeat is still processed ahead of it`() {
        val queue = queueOf(nowMs - 30, nowMs - 20)
        var arrived = false
        val seen = mutableListOf<Long>()

        drainTicksAheadOf(queue, nowMs) {
            seen += it.tick.timestamp
            if (!arrived) {
                queue.add(tick(nowMs - 5))
                arrived = true
            }
        }

        assertThat(seen).containsExactly(nowMs - 30, nowMs - 20, nowMs - 5)
    }

    @Test
    fun `late stamps never keep it draining past one more queue's worth`() {
        val queue = queueOf(nowMs - 3, nowMs - 2, nowMs - 1)

        var arrived = 0

        val processed = drainTicksAheadOf(queue, nowMs) { if (arrived++ < 10_000) queue.add(tick(nowMs - 1)) }

        assertThat(processed).isEqualTo(3 + 100)
    }

    @Test
    fun `an empty queue processes nothing`() {
        assertThat(drainTicksAheadOf(queueOf(), nowMs) { error("no tick") }).isZero()
    }
}
