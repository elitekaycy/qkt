package com.qkt.broker.mt5

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5ReadCacheTest {
    @Test
    fun `successful reads are reused only within ttl`() {
        var now = 0L
        val loads = AtomicInteger()
        val cache = MT5ReadCache(ttlMs = 500L, nanoTime = { now })

        assertThat(cache.get("positions") { "v${loads.incrementAndGet()}" }).isEqualTo("v1")
        assertThat(cache.get("positions") { "v${loads.incrementAndGet()}" }).isEqualTo("v1")

        now = TimeUnit.MILLISECONDS.toNanos(501L)
        assertThat(cache.get("positions") { "v${loads.incrementAndGet()}" }).isEqualTo("v2")
        assertThat(loads).hasValue(2)
    }

    @Test
    fun `failed reads are never cached`() {
        val loads = AtomicInteger()
        val cache = MT5ReadCache(ttlMs = 500L)

        assertThat(
            cache.get("account") {
                loads.incrementAndGet()
                null
            },
        ).isNull()
        assertThat(
            cache.get("account") {
                loads.incrementAndGet()
                "healthy"
            },
        ).isEqualTo("healthy")
        assertThat(loads).hasValue(2)
    }

    @Test
    fun `concurrent misses perform one venue read`() {
        val loads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = MT5ReadCache(ttlMs = 500L)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                executor.submit<String?> {
                    cache.get("orders") {
                        loads.incrementAndGet()
                        entered.countDown()
                        release.await(2, TimeUnit.SECONDS)
                        "snapshot"
                    }
                }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            val second = executor.submit<String?> { cache.get("orders") { "duplicate" } }
            release.countDown()

            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("snapshot")
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo("snapshot")
            assertThat(loads).hasValue(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `clear prevents an in-flight read from repopulating stale state`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = MT5ReadCache(ttlMs = 500L)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val inFlight =
                executor.submit<String?> {
                    cache.get("positions") {
                        entered.countDown()
                        release.await(2, TimeUnit.SECONDS)
                        "before-write"
                    }
                }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()

            cache.clear()
            release.countDown()

            assertThat(inFlight.get(2, TimeUnit.SECONDS)).isEqualTo("before-write")
            assertThat(cache.get("positions") { "after-write" }).isEqualTo("after-write")
        } finally {
            executor.shutdownNow()
        }
    }
}
