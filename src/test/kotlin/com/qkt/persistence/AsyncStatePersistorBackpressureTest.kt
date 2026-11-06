package com.qkt.persistence

import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AsyncStatePersistorBackpressureTest {
    /** Delegate whose first PnL write blocks the writer thread until [release] opens. */
    private class GatedStore : StatePersistor by NoopStatePersistor() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val written = CopyOnWriteArrayList<BigDecimal>()

        override fun savePnl(
            strategyId: String,
            state: PersistedPnl,
        ) {
            if (written.isEmpty() && started.count == 1L) {
                started.countDown()
                release.await()
            }
            written += state.realized
        }
    }

    private fun waitUntilBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
        assertThat(thread.state).isEqualTo(Thread.State.WAITING)
    }

    @Test
    fun `a full queue makes the submitter wait and keeps writes in order`() {
        val store = GatedStore()
        AsyncStatePersistor(store, queueCapacity = 1).use { async ->
            async.savePnl("a", PersistedPnl(BigDecimal("1")))
            assertThat(store.started.await(10, TimeUnit.SECONDS)).isTrue
            async.savePnl("a", PersistedPnl(BigDecimal("2")))
            val submitter = Thread { async.savePnl("a", PersistedPnl(BigDecimal("3"))) }
            submitter.start()
            waitUntilBlocked(submitter)

            assertThat(async.callerRunsTotal).isEqualTo(1L)
            assertThat(async.queueSize).isEqualTo(1)
            store.release.countDown()
            submitter.join(10_000L)
            assertThat(async.awaitDrain()).isTrue
        }
        assertThat(store.written).containsExactly(BigDecimal("1"), BigDecimal("2"), BigDecimal("3"))
    }

    @Test
    fun `an interrupted waiting submitter gives up its write and keeps the interrupt flag`() {
        val store = GatedStore()
        val flagAfterSubmit = CountDownLatch(1)
        AsyncStatePersistor(store, queueCapacity = 1).use { async ->
            async.savePnl("a", PersistedPnl(BigDecimal("1")))
            assertThat(store.started.await(10, TimeUnit.SECONDS)).isTrue
            async.savePnl("a", PersistedPnl(BigDecimal("2")))
            val submitter =
                Thread {
                    async.savePnl("a", PersistedPnl(BigDecimal("3")))
                    if (Thread.currentThread().isInterrupted) flagAfterSubmit.countDown()
                }
            submitter.start()
            waitUntilBlocked(submitter)
            submitter.interrupt()
            submitter.join(10_000L)

            assertThat(flagAfterSubmit.count).isZero
            store.release.countDown()
            assertThat(async.awaitDrain()).isTrue
        }
        assertThat(store.written).containsExactly(BigDecimal("1"), BigDecimal("2"))
    }

    @Test
    fun `close abandons queued writes once the shutdown timeout passes`() {
        val store = GatedStore()
        val async = AsyncStatePersistor(store, shutdownTimeoutMs = 1L)
        async.savePnl("a", PersistedPnl(BigDecimal("1")))
        assertThat(store.started.await(10, TimeUnit.SECONDS)).isTrue
        async.savePnl("a", PersistedPnl(BigDecimal("2")))

        async.close()

        assertThat(store.written).doesNotContain(BigDecimal("2"))
    }
}
