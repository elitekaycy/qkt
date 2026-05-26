package com.qkt.observability

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatencyTrackerTest {
    @Test
    fun `empty tracker returns zero snapshot`() {
        val tracker = LatencyTracker(capacity = 16)
        val s = tracker.snapshot()
        assertThat(s.count).isEqualTo(0)
        assertThat(s.p50Nanos).isEqualTo(0)
        assertThat(s.p95Nanos).isEqualTo(0)
        assertThat(s.p99Nanos).isEqualTo(0)
        assertThat(s.maxNanos).isEqualTo(0)
    }

    @Test
    fun `single observation reports itself at every percentile`() {
        val tracker = LatencyTracker(capacity = 16)
        tracker.observe(5_000)
        val s = tracker.snapshot()
        assertThat(s.count).isEqualTo(1)
        assertThat(s.p50Nanos).isEqualTo(5_000)
        assertThat(s.p95Nanos).isEqualTo(5_000)
        assertThat(s.p99Nanos).isEqualTo(5_000)
        assertThat(s.maxNanos).isEqualTo(5_000)
    }

    @Test
    fun `percentiles are read in sorted order`() {
        val tracker = LatencyTracker(capacity = 128)
        (1..100).shuffled().forEach { tracker.observe(it.toLong()) }
        val s = tracker.snapshot()
        assertThat(s.count).isEqualTo(100)
        // For 100 samples, idx p50 = 49, p95 = 94, p99 = 98, max = 99 → sorted values 50, 95, 99, 100.
        assertThat(s.p50Nanos).isEqualTo(50L)
        assertThat(s.p95Nanos).isEqualTo(95L)
        assertThat(s.p99Nanos).isEqualTo(99L)
        assertThat(s.maxNanos).isEqualTo(100L)
    }

    @Test
    fun `ring buffer overwrites oldest observations when full`() {
        val tracker = LatencyTracker(capacity = 4)
        listOf(1L, 2L, 3L, 4L, 5L, 6L).forEach { tracker.observe(it) }
        // Ring is 4-deep, so the buffer holds 3, 4, 5, 6 after the writes.
        val s = tracker.snapshot()
        assertThat(s.count).isEqualTo(4)
        assertThat(s.maxNanos).isEqualTo(6L)
        // Median over [3,4,5,6] sorted: idx 1 → 4.
        assertThat(s.p50Nanos).isEqualTo(4L)
    }

    @Test
    fun `reset clears all observations`() {
        val tracker = LatencyTracker(capacity = 8)
        tracker.observe(100)
        tracker.observe(200)
        assertThat(tracker.count()).isEqualTo(2)
        tracker.reset()
        assertThat(tracker.count()).isEqualTo(0)
        assertThat(tracker.snapshot().maxNanos).isEqualTo(0)
    }

    @Test
    fun `time inline helper records the block duration`() {
        val tracker = LatencyTracker(capacity = 4)
        val out =
            tracker.time {
                Thread.sleep(2)
                42
            }
        assertThat(out).isEqualTo(42)
        assertThat(tracker.count()).isEqualTo(1)
        // 2ms ≈ 2_000_000ns, but JVM scheduling skews this; sanity-check it landed > 0.
        assertThat(tracker.snapshot().maxNanos).isGreaterThan(0)
    }

    @Test
    fun `observe survives cursor wraparound past Int MAX_VALUE`() {
        val tracker = LatencyTracker(capacity = 4)
        // Pre-seed the cursor to Int.MIN_VALUE + 1 to simulate the post-overflow
        // state after ~2.1B observe calls. With the old `idx % capacity`, this
        // returns -3 → ArrayIndexOutOfBoundsException. With floorMod, it lands at 1.
        val cursorField =
            LatencyTracker::class.java.getDeclaredField("cursor").apply { isAccessible = true }
        (cursorField.get(tracker) as AtomicInteger).set(Int.MIN_VALUE + 1)

        tracker.observe(100)
        tracker.observe(200)
        tracker.observe(300)
        tracker.observe(400)

        val s = tracker.snapshot()
        assertThat(s.count).isEqualTo(4)
        assertThat(s.maxNanos).isEqualTo(400L)
    }

    @Test
    fun `concurrent observe calls are lock-free and lose no observations`() {
        val tracker = LatencyTracker(capacity = 4096)
        val threadCount = 8
        val perThread = 100
        val pool = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        for (t in 0 until threadCount) {
            pool.submit {
                start.await()
                repeat(perThread) { tracker.observe((t * perThread + it).toLong()) }
                done.countDown()
            }
        }
        start.countDown()
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()

        val expected = threadCount * perThread
        // Total writes = 800, but capacity = 4096 so all should be retained.
        assertThat(tracker.count()).isEqualTo(expected)
    }
}
