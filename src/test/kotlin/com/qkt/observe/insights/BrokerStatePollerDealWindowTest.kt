package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerDealWindowTest : BrokerStatePollerFixture() {
    @Test
    fun `poller rejects stale deals returned outside the requested millisecond range`() {
        var now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.ignoreDealRange = true
        broker.allDeals = listOf(deal("1", ts = now - 1_000L))
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                backfillDays = 1L,
                clock = { now },
            )

        poller.pollOnce()
        assertThat(collectBodies("deal-FAKE-1")).contains("deal-FAKE-1")
        now += 1_000L
        poller.pollOnce()

        assertThat(collectBodies("posn-FAKE-1700000001000")).doesNotContain("deal-FAKE-1")
    }

    @Test
    fun `a deal booked late but inside the grace window is still emitted`() {
        val firstNow = 1_700_000_000_000L
        var now = firstNow
        val broker = FakeBroker()
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                backfillDays = 1L,
                clock = { now },
            )
        poller.pollOnce()

        // Booked retroactively with a timestamp one minute old — inside the 5-minute grace.
        broker.allDeals = listOf(deal("12", ts = firstNow - 60_000L))
        now += 10_000L
        poller.pollOnce()
        assertThat(collectBodies("deal-FAKE-12")).contains("deal-FAKE-12")
    }

    @Test
    fun `a deal booked late beyond the grace window is dropped by contract`() {
        val firstNow = 1_700_000_000_000L
        var now = firstNow
        val broker = FakeBroker()
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                backfillDays = 1L,
                clock = { now },
            )
        poller.pollOnce()

        // Booked retroactively past the grace edge: the watermark has moved beyond its
        // timestamp, so it is never fetched. This pins the documented grace trade-off.
        broker.allDeals = listOf(deal("13", ts = firstNow - 6 * 60_000L))
        now += 10_000L
        poller.pollOnce()
        val second = broker.dealCalls[1]
        assertThat(second.first).isGreaterThan(firstNow - 6 * 60_000L)
    }
}
