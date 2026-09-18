package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerWatermarkTest : BrokerStatePollerFixture() {
    @Test
    fun `backfill emits every deal in the window once, later cycles only new ones`() {
        var now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.allDeals =
            listOf(
                deal("0", ts = now - 2 * 86_400_000L),
                deal("1", ts = now - 2_000L),
                deal("2", ts = now - 1_000L),
            )
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
        val first = collectBodies("deal-FAKE-1", "deal-FAKE-2")
        assertThat(first).contains("deal-FAKE-1").contains("deal-FAKE-2")
        // Outside the one-day backfill window — never fetched.
        assertThat(first).doesNotContain("deal-FAKE-0")

        now += 10_000L
        broker.allDeals = broker.allDeals + deal("3", ts = now - 500L)
        poller.pollOnce()
        val second = collectBodies("deal-FAKE-3")
        assertThat(second).contains("deal-FAKE-3")
        // Already shipped in the first cycle; the cursor advanced past them.
        assertThat(second).doesNotContain("deal-FAKE-1")
        assertThat(second).doesNotContain("deal-FAKE-2")
    }

    @Test
    fun `a deal closing a just-vanished position is attributed before the prune`() {
        // The venue overwrites SL/TP close comments ("[tp 4332.689]"), so the owner
        // map is the only attribution source for a position that closed between
        // cycles: it is gone from positionTickets() but its deal arrives this cycle.
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.tickets = emptyList()
        broker.allDeals =
            listOf(deal("77", ts = now - 500L, positionTicket = "T9", comment = "[tp 4332.689]"))
        val attribution = TicketAttribution()
        attribution.record("T9", "hedge_straddle")
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = attribution,
                deployedIds = { listOf("hedge_straddle") },
                backfillDays = 1L,
                clock = { now },
            )
        poller.pollOnce()
        val all = collectBodies("deal-FAKE-77")
        val dealEntry = all.substringAfter("deal-FAKE-77").substringBefore("}}")
        assertThat(dealEntry).contains(""""strategyId":"hedge_straddle"""")
        // Retained briefly after the fetch: MT5 may expose related close/cost rows late.
        assertThat(attribution.ownerOf("T9")).isEqualTo("hedge_straddle")
    }

    @Test
    fun `delayed close deal keeps the vanished ticket owner`() {
        var now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.tickets = listOf(ticket("T9", comment = "dsl-hedge_straddle"))
        broker.allDeals = listOf(deal("open", ts = now - 500L, positionTicket = "T9", comment = "dsl-hedge_straddle"))
        val attribution = TicketAttribution()
        attribution.record("T9", "hedge_straddle")
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = attribution,
                deployedIds = { listOf("hedge_straddle") },
                backfillDays = 1L,
                clock = { now },
            )

        poller.pollOnce()
        collectBodies("deal-FAKE-open")

        now += 1_000L
        broker.tickets = emptyList()
        broker.allDeals = broker.allDeals
        poller.pollOnce()
        assertThat(attribution.ownerOf("T9")).isEqualTo("hedge_straddle")

        now += 1_000L
        broker.allDeals =
            broker.allDeals + deal("close", ts = now - 500L, positionTicket = "T9", comment = "")
        poller.pollOnce()

        val all = collectBodies("deal-FAKE-close")
        val closeEntry = all.substringAfter("deal-FAKE-close").substringBefore("}}")
        assertThat(closeEntry).contains(""""strategyId":"hedge_straddle"""")
    }

    @Test
    fun `profile brokers sharing one venue account fetch and emit deals once per cycle`() {
        val now = 1_700_000_000_000L
        val first = FakeBroker()
        val second = FakeBroker()
        second.account = second.account!!.copy(broker = "FAKE2")
        val shared = listOf(deal("7", ts = now - 1_000L))
        first.allDeals = shared
        second.allDeals = shared
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
        assertThat(second.dealCalls).isEmpty()
        val bodies = collectBodies("deal-FAKE-7")
        assertThat(Regex("\"deal-FAKE-7\"").findAll(bodies).count()).isEqualTo(1)
        assertThat(bodies).doesNotContain("deal-FAKE2-7")
    }
}
