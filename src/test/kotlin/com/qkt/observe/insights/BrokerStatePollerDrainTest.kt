package com.qkt.observe.insights

import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerDrainTest : BrokerStatePollerFixture() {
    private val now = 1_700_000_000_000L
    private val broker = FakeBroker()

    private fun poller() =
        BrokerStatePoller(
            brokers = listOf(broker),
            sink = sink,
            attribution = TicketAttribution(),
            deployedIds = { emptyList() },
            backfillDays = 1L,
            clock = { now },
        )

    @Test
    fun `a drain with default limits returns after one complete pass`() {
        broker.allDeals = listOf(deal("last", ts = now - 500L))

        poller().drainAndClose()

        assertThat(broker.dealCalls).hasSize(1)
        assertThat(collectBodies("deal-FAKE-last")).contains("deal-FAKE-last")
    }

    @Test
    fun `an interrupted drain stops waiting and keeps the interrupt`() {
        broker.failDeals = true
        val p = poller()
        val interruptedAfter = AtomicBoolean(false)
        val drainer =
            Thread {
                p.drainAndClose(timeoutMs = 60_000L, retryMs = 60_000L)
                interruptedAfter.set(Thread.currentThread().isInterrupted)
            }
        drainer.start()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (drainer.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
        drainer.interrupt()
        drainer.join(10_000L)

        assertThat(drainer.isAlive).isFalse
        assertThat(interruptedAfter.get()).isTrue
    }
}
