package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerCommentAttributionTest : BrokerStatePollerFixture() {
    @Test
    fun `backfilled close deal inherits its opening deal's comment attribution`() {
        // After a restart the ticket map is empty; the venue overwrote the close comment.
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.allDeals =
            listOf(
                deal("open-9", ts = now - 5_000L, positionTicket = "P9", comment = "dsl-gold_ema_pullback--57"),
                deal("close-9", ts = now - 1_000L, positionTicket = "P9", comment = "[tp 4526.32]", entry = "OUT"),
            )
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { listOf("gold_ema_pullback") },
                backfillDays = 1L,
                clock = { now },
            )
        poller.pollOnce()
        val all = collectBodies("deal-FAKE-open-9", "deal-FAKE-close-9")
        val close = all.substringAfter("deal-FAKE-close-9").substringBefore("}}")
        assertThat(close).contains(""""strategyId":"gold_ema_pullback"""")
    }

    @Test
    fun `every broker deal is emitted, attributed when known and without a strategy otherwise (#1143)`() {
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.allDeals =
            listOf(
                deal("local-ticket", ts = now - 4_000L, positionTicket = "P_LOCAL", comment = "[tp 4332.689]"),
                deal("local-comment", ts = now - 3_000L, comment = "dsl-local_strat"),
                deal("foreign-ticket", ts = now - 2_000L, positionTicket = "P_FOREIGN", comment = "[tp 4332.689]"),
                deal("unknown", ts = now - 1_000L, comment = null),
            )
        val attribution = TicketAttribution()
        attribution.record("P_LOCAL", "local_strat")
        attribution.record("P_FOREIGN", "foreign_strat")
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = attribution,
                deployedIds = { listOf("local_strat") },
                backfillDays = 1L,
                clock = { now },
            )

        poller.pollOnce()

        val all =
            collectBodies(
                "deal-FAKE-local-ticket",
                "deal-FAKE-local-comment",
                "deal-FAKE-foreign-ticket",
                "deal-FAKE-unknown",
            )

        fun body(id: String) = all.substringAfter(id).substringBefore("}}")
        assertThat(body("deal-FAKE-local-ticket")).contains(""""strategyId":"local_strat"""")
        assertThat(body("deal-FAKE-local-comment")).contains(""""strategyId":"local_strat"""")
        // Owned by a strategy this daemon no longer runs: still its deal, sent under that owner.
        assertThat(body("deal-FAKE-foreign-ticket")).contains(""""strategyId":"foreign_strat"""")
        // No owner known in this process (e.g. a close after a restart): sent without a strategy.
        assertThat(body("deal-FAKE-unknown")).doesNotContain("strategyId")
    }

    @Test
    fun `truncated comment shared by daemon siblings stays unattributed without a ticket owner`() {
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.tickets = listOf(ticket("T1", comment = "dsl-run_20260810_common_pref"))
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = {
                    listOf(
                        "run_20260810_common_prefix_bars_readonly",
                        "run_20260810_common_prefix_market_bracket",
                    )
                },
                clock = { now },
            )

        poller.pollOnce()

        val all = collectBodies("posn-FAKE-1700000000000")
        val position = all.substringAfter(""""ticket":"T1"""").substringBefore("}")
        assertThat(position)
            .doesNotContain("strategyId")
            .doesNotContain("bars_readonly")
            .doesNotContain("market_bracket")
    }
}
