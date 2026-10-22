package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The deal poll re-reads an overlap window every cycle and sends each deal once by its ticket, and its
 * watermark only moves on a read that succeeded: a deal the venue lists late, behind a newer one, or
 * while its reads were failing, still reaches the collector. A stopping session drains the deals its own
 * flatten made before it lets go of the venue (an MT5 close was lost live, 2026-10-21, while reads timed out).
 */
class BrokerStatePollerDealCursorTest : BrokerStatePollerFixture() {
    private var now = 1_700_000_000_000L
    private val broker = FakeBroker()
    private val poller by lazy {
        BrokerStatePoller(
            brokers = listOf(broker),
            sink = sink,
            attribution = TicketAttribution(),
            deployedIds = { emptyList() },
            backfillDays = 1L,
            clock = { now },
        )
    }

    @Test
    fun `a deal listed late behind a newer one is still sent, and no deal twice`() {
        broker.allDeals = listOf(deal("new", ts = now - 1_000L))
        poller.pollOnce()
        collectBodies("deal-FAKE-new")

        now += 10_000L
        broker.allDeals = broker.allDeals + deal("older", ts = now - 12_000L)
        poller.pollOnce()

        val second = collectBodies("deal-FAKE-older")
        assertThat(second).contains("deal-FAKE-older").doesNotContain("deal-FAKE-new")
    }

    @Test
    fun `reads failing for ten minutes keep the watermark, and the deal is sent once they recover`() {
        poller.pollOnce()
        broker.allDeals = listOf(deal("late", ts = now + 5_000L))
        broker.failDeals = true
        repeat(60) {
            now += 10_000L
            poller.pollOnce()
        }
        broker.failDeals = false
        now += 10_000L
        poller.pollOnce()

        assertThat(collectBodies("deal-FAKE-late")).contains("deal-FAKE-late")
    }

    @Test
    fun `a stopping poller drains the deals its reads kept missing before it closes`() {
        broker.allDeals = listOf(deal("flatten", ts = now - 500L))
        broker.failNextDeals = 2

        poller.drainAndClose(timeoutMs = 5_000L, retryMs = 10L)

        assertThat(broker.dealCalls).hasSize(3)
        assertThat(collectBodies("deal-FAKE-flatten")).contains("deal-FAKE-flatten")
    }
}
