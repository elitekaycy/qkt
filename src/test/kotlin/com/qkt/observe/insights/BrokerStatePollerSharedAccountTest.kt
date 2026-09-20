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
        assertThat(broker.dealCalls[0].first).isEqualTo(firstNow - 30L * 86_400_000L + 1)

        now += 10_000L
        poller.pollOnce()
        // The second window starts at the previous cycle's grace edge, not back at the
        // 30-day seed — a quiet account no longer re-fetches a month per cycle.
        assertThat(broker.dealCalls).hasSize(2)
        assertThat(broker.dealCalls[1].first).isEqualTo(firstNow - 5 * 60_000L + 1)
    }

    @Test
    fun `a found deal newer than the grace edge keeps owning the watermark`() {
        var now = 1_700_000_000_000L
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
        val dealTs = now - 1_000L
        now += 10_000L
        poller.pollOnce()
        assertThat(broker.dealCalls[1].first).isEqualTo(dealTs + 1)
    }

    @Test
    fun `brokers on different venue accounts fetch deals independently`() {
        val now = 1_700_000_000_000L
        val first = FakeBroker()
        val second = FakeBroker()
        second.account = second.account!!.copy(login = 999_111_222L)
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
