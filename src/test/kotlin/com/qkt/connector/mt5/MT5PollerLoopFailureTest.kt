package com.qkt.connector.mt5

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A poll round that throws, of any kind, never ends the poller thread: it logs and polls again. */
class MT5PollerLoopFailureTest {
    private val gateway = Mt5RoutedGateway()
    private val profile =
        MT5BrokerProfile(
            name = "loop",
            gatewayUrl = gateway.url,
            symbolPolicy = SymbolPolicy(),
            magic = 1,
            httpTimeoutMs = 2_000,
            retryAttempts = 0,
            pollIntervalMs = 5,
        )
    private val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)

    @AfterEach
    fun stop() = gateway.close()

    /** A gate that throws an IO failure, then a runtime one, then lets [done] know the loop survived both. */
    private fun gate(done: CountDownLatch): (java.time.Instant) -> Boolean {
        val calls = AtomicInteger()
        return {
            when (calls.incrementAndGet()) {
                1 -> throw IOException("gateway unreachable")
                2 -> throw IllegalStateException("bad round")
                else -> {
                    done.countDown()
                    false
                }
            }
        }
    }

    @Test
    fun `the position poller survives an IO failure and a runtime failure in a round`() {
        val done = CountDownLatch(1)
        val poller =
            MT5PositionPoller(
                client,
                profile,
                MT5Symbol(profile.symbolPolicy),
                bus(),
                clock(),
                sessionGate = gate(done),
            )

        poller.start()
        try {
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        } finally {
            poller.stop()
        }
    }

    @Test
    fun `the pending order poller survives an IO failure and a runtime failure in a round`() {
        val done = CountDownLatch(1)
        val poller = MT5PendingOrderPoller(client, profile, sessionGate = gate(done))

        poller.start()
        try {
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        } finally {
            poller.stop()
        }
    }

    private fun clock() = com.qkt.common.FixedClock(time = 1_800_000_000_000L)

    private fun bus() = com.qkt.bus.EventBus(clock(), com.qkt.common.MonotonicSequenceGenerator())
}
