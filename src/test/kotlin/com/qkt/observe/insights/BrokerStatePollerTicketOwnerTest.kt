package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerTicketOwnerTest : BrokerStatePollerFixture() {
    @Test
    fun `recorded ticket owner wins over the comment fallback`() {
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.tickets =
            listOf(
                ticket("T1", comment = "dsl-other_strat"),
                ticket("T2", comment = "dsl-other_st"),
            )
        broker.allDeals = listOf(deal("9", ts = now - 1_000L, positionTicket = "T1", comment = "dsl-other_strat"))
        val attribution = TicketAttribution()
        attribution.record("T1", "mapped_strat")
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = attribution,
                deployedIds = { listOf("other_strat", "mapped_strat") },
                backfillDays = 1L,
                clock = { now },
            )
        poller.pollOnce()
        val all = collectBodies("deal-FAKE-9", "posn-FAKE-")
        // T1 is owned via the fill record; its comment would have said other_strat.
        assertThat(all).contains(""""ticket":"T1","symbol":"FAKE:XAUUSD","side":"BUY"""")
        assertThat(all).contains(""""ticket":"T1"""")
        val t1Entry = all.substringAfter(""""ticket":"T1"""").substringBefore("}")
        assertThat(t1Entry).contains(""""strategyId":"mapped_strat"""")
        // T2 has no record; the truncated comment matches other_strat uniquely.
        val t2Entry = all.substringAfter(""""ticket":"T2"""").substringBefore("}")
        assertThat(t2Entry).contains(""""strategyId":"other_strat"""")
        // The deal references position T1 → same owner-first priority.
        val dealEntry = all.substringAfter("deal-FAKE-9").substringBefore("}}")
        assertThat(dealEntry).contains(""""strategyId":"mapped_strat"""")
    }

    @Test
    fun `attribution map keeps vanished tickets briefly then prunes them`() {
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.tickets = listOf(ticket("KEEP"))
        val attribution = TicketAttribution()
        attribution.record("KEEP", "a")
        attribution.record("GONE", "b")
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = attribution,
                deployedIds = { emptyList() },
                clock = { now },
            )
        poller.pollOnce()
        assertThat(attribution.ownerOf("KEEP")).isEqualTo("a")
        assertThat(attribution.ownerOf("GONE")).isEqualTo("b")
        repeat(301) { poller.pollOnce() }
        assertThat(attribution.ownerOf("KEEP")).isEqualTo("a")
        assertThat(attribution.ownerOf("GONE")).isNull()
    }

    @Test
    fun `a failed deal fetch does not advance the watermark or lose deals`() {
        val firstNow = 1_700_000_000_000L
        var now = firstNow
        val broker = FakeBroker()
        broker.failDeals = true
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
        assertThat(broker.dealCalls).hasSize(1)

        // A deal booked between the failed cycle and the retry must still be caught:
        // the watermark stays at the seed, so the retry re-covers the full window.
        broker.failDeals = false
        broker.allDeals = listOf(deal("11", ts = now - 500L))
        now += 10_000L
        poller.pollOnce()
        assertThat(broker.dealCalls[1].first).isEqualTo(broker.dealCalls[0].first)
        assertThat(collectBodies("deal-FAKE-11")).contains("deal-FAKE-11")
    }

    @Test
    fun `one broker's failed fetch defers the shared account to the next cycle`() {
        val now = 1_700_000_000_000L
        val first = FakeBroker()
        val second = FakeBroker()
        second.account = second.account!!.copy(broker = "FAKE2")
        first.failDeals = true
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
        // The account was claimed by the first broker before its fetch failed; the
        // second must not double-claim within the same cycle. The retry happens next
        // cycle with an unadvanced watermark, so nothing is lost.
        assertThat(first.dealCalls).hasSize(1)
        assertThat(second.dealCalls).isEmpty()
        poller.pollOnce()
        assertThat(first.dealCalls).hasSize(2)
    }
}
