package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SharedBarRequestsJoinTest {
    private val window = TimeWindow(60_000L)
    private val range = probeRange()
    private val key = BarRequest("X", 60_000L, 0L, 60_000L)

    /** A delegate whose first read blocks until [failWith] hands it the failure to throw. */
    private class BlockingFailure : MarketSource {
        override val name = "blocking"
        override val capabilities = setOf(MarketSourceCapability.BARS)
        val started = CountDownLatch(1)
        val failure = CompletableFuture<Throwable>()

        override fun supports(symbol: String) = true

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> {
            started.countDown()
            throw failure.get(5, TimeUnit.SECONDS)
        }
    }

    private fun requests(delegate: MarketSource) =
        SharedBarRequests(delegate, 1_000L, 8, FixedClock(0L), LoggerFactory.getLogger("test"))

    /** Runs a joiner against a leader blocked in [delegate], fails the leader, returns what the joiner saw. */
    private fun joinerOutcome(failure: Throwable): Throwable? {
        val delegate = BlockingFailure()
        val shared = requests(delegate)
        val leader = Thread { runCatching { shared.loadOrJoin(key, "X", window, range) } }.apply { start() }
        assertThat(delegate.started.await(5, TimeUnit.SECONDS)).isTrue()
        val seen = CompletableFuture<Throwable?>()
        val joiner =
            Thread {
                seen.complete(runCatching { shared.loadOrJoin(key, "X", window, range) }.exceptionOrNull())
            }.apply { start() }
        awaitParked(joiner)
        delegate.failure.complete(failure)
        leader.join(5_000L)
        return seen.get(5, TimeUnit.SECONDS)
    }

    private fun awaitParked(thread: Thread) {
        while (thread.state != Thread.State.WAITING) Thread.onSpinWait()
    }

    @Test
    fun `a joiner sees the leader's runtime failure itself`() {
        val failure = IllegalArgumentException("bad range")

        assertThat(joinerOutcome(failure)).isSameAs(failure)
    }

    @Test
    fun `a joiner sees the leader's error itself`() {
        val failure = Error("out of memory")

        assertThat(joinerOutcome(failure)).isSameAs(failure)
    }

    @Test
    fun `a joiner wraps the leader's checked failure`() {
        val failure = IOException("socket reset")

        val seen = joinerOutcome(failure)

        assertThat(seen).isInstanceOf(IllegalStateException::class.java).hasMessage("shared bar request failed")
        assertThat(seen!!.cause).isSameAs(failure)
    }

    @Test
    fun `an interrupted joiner fails and keeps its interrupt flag`() {
        val delegate = BlockingFailure()
        val shared = requests(delegate)
        val leader = Thread { runCatching { shared.loadOrJoin(key, "X", window, range) } }.apply { start() }
        assertThat(delegate.started.await(5, TimeUnit.SECONDS)).isTrue()
        val seen = CompletableFuture<Pair<Throwable?, Boolean>>()
        val joiner =
            Thread {
                val failure = runCatching { shared.loadOrJoin(key, "X", window, range) }.exceptionOrNull()
                seen.complete(failure to Thread.currentThread().isInterrupted)
            }.apply { start() }
        awaitParked(joiner)

        joiner.interrupt()
        val (failure, interrupted) = seen.get(5, TimeUnit.SECONDS)
        delegate.failure.complete(IllegalStateException("done"))
        leader.join(5_000L)

        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("interrupted")
        assertThat(interrupted).isTrue()
    }

    @Test
    fun `clearing drops cached reads so the next read loads again`() {
        val probe = DelegationProbe()
        val shared = requests(probe)
        shared.loadOrJoin(key, "X", window, range)

        shared.clear()
        shared.loadOrJoin(key, "X", window, range)

        assertThat(probe.calls).containsExactly("bars:X:60000", "bars:X:60000")
    }
}
