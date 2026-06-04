package com.qkt.marketdata.live

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.util.Collections
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * #65 — load/stress coverage for [LiveTickFeed], the live ingestion path.
 *
 * The feed buffers ticks from the source thread in a bounded queue and applies
 * drop-oldest backpressure: when the buffer is full the oldest tick is shed so a
 * fast producer (a crypto burst) can never grow the buffer without bound. The
 * unit tests cover this with three ticks; these push it to scale and under a real
 * producer/consumer race, where the invariants are easy to get subtly wrong.
 *
 * Properties guarded:
 *  - bounded memory: the buffer never exceeds its capacity, no matter how far the
 *    producer outruns the consumer (a burst must not OOM the daemon).
 *  - load shedding: a full buffer drops the OLDEST tick (live trading wants the
 *    freshest price, not a stale backlog), and every eviction is counted once.
 *  - conservation + order under concurrency: with a producer thread racing a
 *    consumer thread, every tick is either delivered exactly once or counted as
 *    dropped, and delivered ticks stay in arrival order.
 *
 * Tagged `stress` so it stays out of default CI (excluded in `build.gradle.kts`).
 * Run via:
 *
 *   ./gradlew test -PincludeTags=stress --tests 'com.qkt.marketdata.live.LiveTickFeedLoadTest'
 */
@Tag("stress")
class LiveTickFeedLoadTest {
    private fun tick(ts: Long) = Tick("X", Money.of("100"), ts)

    /** Source whose `onTick` we drive directly to simulate the feed thread. */
    private class FakeSource : LiveTickSource {
        var onTick: ((Tick) -> Unit)? = null

        override fun start(
            onTick: (Tick) -> Unit,
            onError: (Throwable) -> Unit,
            onDisconnect: () -> Unit,
            onReconnect: () -> Unit,
        ) {
            this.onTick = onTick
        }

        override fun stop() {}
    }

    @Test
    fun `a flood far beyond capacity stays bounded, sheds oldest, and conserves every tick`() {
        val capacity = 10_000
        val total = 100_000
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = capacity)

        for (i in 0 until total) src.onTick!!(tick(i.toLong()))
        feed.close()

        val delivered = generateSequence { feed.next() }.map { it.timestamp }.toList()

        // Bounded: exactly `capacity` survive in the buffer; the rest were shed.
        assertThat(delivered).hasSize(capacity)
        assertThat(feed.droppedTicks.get()).isEqualTo((total - capacity).toLong())
        // Conservation: nothing vanishes uncounted.
        assertThat(delivered.size + feed.droppedTicks.get()).isEqualTo(total.toLong())
        // Drop-OLDEST: survivors are the newest `capacity` ticks, in arrival order.
        assertThat(delivered).isEqualTo((total - capacity until total).map { it.toLong() })
    }

    @Test
    fun `a consumer keeping pace under high volume loses nothing and preserves order`() {
        val capacity = 10_000
        val total = 200_000
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = capacity)

        val delivered = ArrayList<Long>(total)
        // Interleave produce/consume 1:1 so the buffer never fills — proves zero loss
        // when the strategy keeps up with the feed.
        for (i in 0 until total) {
            src.onTick!!(tick(i.toLong()))
            feed.next()?.let { delivered.add(it.timestamp) }
        }
        feed.close()
        generateSequence { feed.next() }.forEach { delivered.add(it.timestamp) }

        assertThat(feed.droppedTicks.get()).isZero()
        assertThat(delivered).hasSize(total)
        assertThat(delivered).isEqualTo((0 until total).map { it.toLong() })
    }

    @Test
    fun `a concurrent flood delivers or counts every tick exactly once, in order`() {
        val capacity = 1_000
        val total = 500_000
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = capacity, pollIntervalMs = 5)

        val delivered = Collections.synchronizedList(ArrayList<Long>(total))
        val consumer =
            Thread {
                while (true) {
                    val t = feed.next() ?: break
                    delivered.add(t.timestamp)
                }
            }
        consumer.start()

        val producer =
            Thread {
                for (i in 0 until total) src.onTick!!(tick(i.toLong()))
            }
        producer.start()
        producer.join()

        // Buffer drained once every produced tick is accounted for (delivered or dropped).
        val drained =
            awaitUntil(timeoutMs = 30_000) {
                delivered.size + feed.droppedTicks.get() == total.toLong()
            }
        feed.close()
        consumer.join(5_000)

        assertThat(drained)
            .withFailMessage(
                "feed did not drain within 30s: delivered=${delivered.size} " +
                    "dropped=${feed.droppedTicks.get()} total=$total",
            ).isTrue()
        // Conservation: every tick delivered exactly once or counted as dropped.
        assertThat(delivered.size + feed.droppedTicks.get()).isEqualTo(total.toLong())
        // No double-delivery.
        assertThat(delivered.toSet()).hasSize(delivered.size)
        // Order preserved: drop-oldest never reorders survivors.
        assertThat(delivered).isSorted()
    }

    private inline fun awaitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            if (condition()) return true
            Thread.sleep(1)
        }
        return condition()
    }
}
