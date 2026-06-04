package com.qkt.marketdata.live

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveTickFeedTest {
    private fun tick(
        symbol: String,
        ts: Long,
        price: String,
    ) = Tick(symbol, Money.of(price), ts)

    private class FakeSource : LiveTickSource {
        var onTick: ((Tick) -> Unit)? = null
        var onError: ((Throwable) -> Unit)? = null
        var onDisconnect: (() -> Unit)? = null
        var onReconnect: (() -> Unit)? = null
        var stopped: Boolean = false

        override fun start(
            onTick: (Tick) -> Unit,
            onError: (Throwable) -> Unit,
            onDisconnect: () -> Unit,
            onReconnect: () -> Unit,
        ) {
            this.onTick = onTick
            this.onError = onError
            this.onDisconnect = onDisconnect
            this.onReconnect = onReconnect
        }

        override fun stop() {
            stopped = true
        }
    }

    @Test
    fun `next returns ticks in arrival order`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 100)
        src.onTick!!(tick("X", 1L, "100"))
        src.onTick!!(tick("X", 2L, "101"))
        src.onTick!!(tick("X", 3L, "102"))
        feed.close()

        val out = generateSequence { feed.next() }.toList()
        assertThat(out.map { it.timestamp }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `drop oldest when queue is full`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 2)
        src.onTick!!(tick("X", 1L, "100"))
        src.onTick!!(tick("X", 2L, "101"))
        src.onTick!!(tick("X", 3L, "102"))
        feed.close()

        val out = generateSequence { feed.next() }.toList()
        assertThat(out.map { it.timestamp }).containsExactly(2L, 3L)
        assertThat(feed.droppedTicks.get()).isEqualTo(1L)
    }

    @Test
    fun `next blocks until tick arrives or close is signaled`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 10, pollIntervalMs = 50)

        val latch = CountDownLatch(1)
        val received = mutableListOf<Tick?>()
        val t =
            Thread {
                received.add(feed.next())
                latch.countDown()
            }
        t.start()

        Thread.sleep(50)
        src.onTick!!(tick("X", 99L, "100"))
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(received.single()).isNotNull()
        assertThat(received.single()!!.timestamp).isEqualTo(99L)

        feed.close()
    }

    @Test
    fun `close causes next to return null after pending drains`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 10, pollIntervalMs = 25)
        src.onTick!!(tick("X", 1L, "100"))
        feed.close()

        val first = feed.next()
        val second = feed.next()
        assertThat(first).isNotNull()
        assertThat(second).isNull()
    }

    @Test
    fun `close calls underlying source stop`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 10)
        feed.close()
        assertThat(src.stopped).isTrue()
    }

    @Test
    fun `dropped count is observable through droppedTicks`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 1)
        src.onTick!!(tick("X", 1L, "100"))
        src.onTick!!(tick("X", 2L, "101"))
        src.onTick!!(tick("X", 3L, "102"))

        assertThat(feed.droppedTicks.get()).isEqualTo(2L)
    }

    @Test
    fun `next returns null after a disconnect outlasts the reconnect budget`() {
        val src = FakeSource()
        val clock = FixedClock(0L)
        val feed =
            LiveTickFeed(src, queueCapacity = 10, pollIntervalMs = 25, clock = clock, reconnectBudgetMs = 1_000L)
        src.onTick!!(tick("X", 1L, "100"))
        src.onTick!!(tick("X", 2L, "101"))
        src.onDisconnect!!() // disconnectedSince = 0
        clock.time = 2_000L // advance past the 1s budget

        val first = feed.next()
        val second = feed.next()
        val third = feed.next()
        assertThat(first?.timestamp).isEqualTo(1L)
        assertThat(second?.timestamp).isEqualTo(2L)
        assertThat(third).isNull()
    }
}
