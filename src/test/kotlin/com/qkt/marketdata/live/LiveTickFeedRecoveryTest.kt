package com.qkt.marketdata.live

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveTickFeedRecoveryTest {
    private fun tick(ts: Long) = Tick("X", Money.of("100"), ts)

    private class FakeSource : LiveTickSource {
        var onTick: ((Tick) -> Unit)? = null
        var onDisconnect: (() -> Unit)? = null
        var onReconnect: (() -> Unit)? = null

        override fun start(
            onTick: (Tick) -> Unit,
            onError: (Throwable) -> Unit,
            onDisconnect: () -> Unit,
            onReconnect: () -> Unit,
        ) {
            this.onTick = onTick
            this.onDisconnect = onDisconnect
            this.onReconnect = onReconnect
        }

        override fun stop() {}
    }

    @Test
    fun `survives a transient disconnect and resumes ingesting on reconnect`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 10, pollIntervalMs = 10, reconnectBudgetMs = 60_000L)
        src.onDisconnect!!() // disconnected, well within the 60s budget

        val latch = CountDownLatch(1)
        val received = mutableListOf<Tick?>()
        Thread {
            received.add(feed.next()) // blocks: empty queue, disconnected, within budget
            latch.countDown()
        }.start()

        Thread.sleep(50) // let next() spin in the disconnected-within-budget wait
        src.onReconnect!!() // resume
        src.onTick!!(tick(7L)) // a post-reconnect tick

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(received.single()?.timestamp).isEqualTo(7L)
        feed.close()
    }

    @Test
    fun `a reconnect clears the disconnect so the budget never fires`() {
        val src = FakeSource()
        val clock = FixedClock(0L)
        val feed =
            LiveTickFeed(src, queueCapacity = 10, pollIntervalMs = 10, clock = clock, reconnectBudgetMs = 1_000L)
        src.onDisconnect!!()
        src.onReconnect!!() // back before the budget
        clock.time = 5_000L // even past the old budget
        src.onTick!!(tick(3L))

        // Not disconnected any more, and a tick is queued — next returns it, no termination.
        assertThat(feed.next()?.timestamp).isEqualTo(3L)
        feed.close()
    }
}
