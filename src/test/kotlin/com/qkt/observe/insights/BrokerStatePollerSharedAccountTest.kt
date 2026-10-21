package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerSharedAccountTest : BrokerStatePollerFixture() {
    @Test
    fun `sessions sharing an account share one deal fetch per cycle`() {
        val now = 1_700_000_000_000L
        val shared = SharedDealFetch()
        val brokerA = FakeBroker()
        val brokerB = FakeBroker()
        brokerA.allDeals = listOf(deal("1", ts = now - 2_000L))
        brokerB.allDeals = brokerA.allDeals

        fun poller(broker: FakeBroker) =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                backfillDays = 1L,
                clock = { now },
                sharedDeals = shared,
            )

        poller(brokerA).pollOnce()
        poller(brokerB).pollOnce()

        assertThat(brokerA.dealCalls).hasSize(1)
        assertThat(brokerB.dealCalls).isEmpty()
        assertThat(collectBodies("deal-FAKE-1")).contains("deal-FAKE-1")
    }

    @Test
    fun `dealless cycles advance the watermark instead of re-fetching the backfill window`() {
        val firstNow = 1_700_000_000_000L
        var now = firstNow
        val broker = FakeBroker()
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                backfillDays = 30L,
                clock = { now },
            )
        poller.pollOnce()
        assertThat(broker.dealCalls).hasSize(1)
        assertThat(broker.dealCalls[0].first).isEqualTo(firstNow - 30L * 86_400_000L)

        now += 10_000L
        poller.pollOnce()
        // The second window re-reads the grace behind the previous read, not back to the
        // 30-day seed — a quiet account no longer re-fetches a month per cycle.
        assertThat(broker.dealCalls).hasSize(2)
        assertThat(broker.dealCalls[1].first).isEqualTo(firstNow - 5 * 60_000L)
    }

    @Test
    fun `a found deal never moves the next read past the grace behind the last one`() {
        var now = 1_700_000_000_000L
        val firstNow = now
        val broker = FakeBroker()
        broker.allDeals = listOf(deal("9", ts = now - 1_000L))
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
        assertThat(collectBodies("deal-FAKE-9")).contains("deal-FAKE-9")
        now += 10_000L
        poller.pollOnce()
        // A deal the venue lists late, older than deal 9, is still inside the next read.
        assertThat(broker.dealCalls[1].first).isEqualTo(firstNow - 5 * 60_000L)
        assertThat(collectBodies("posn-FAKE-$now")).doesNotContain("deal-FAKE-9")
    }

    @Test
    fun `brokers on different venue accounts fetch deals independently`() {
        val now = 1_700_000_000_000L
        val first = FakeBroker()
        val second = FakeBroker()
        second.account = second.account!!.copy(login = "999111222")
        val poller =
            BrokerStatePoller(
                brokers = listOf(first, second),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                backfillDays = 1L,
                clock = { now },
            )
        poller.pollOnce()
        assertThat(first.dealCalls).hasSize(1)
        assertThat(second.dealCalls).hasSize(1)
    }
}
