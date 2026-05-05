package com.qkt.marketdata.live

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
        var stopped: Boolean = false

        override fun start(
            onTick: (Tick) -> Unit,
            onError: (Throwable) -> Unit,
            onDisconnect: () -> Unit,
        ) {
            this.onTick = onTick
            this.onError = onError
            this.onDisconnect = onDisconnect
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
}
