package com.qkt.observe.insights

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerCycleTest : BrokerStatePollerFixture() {
    @Test
    fun `emits one snapshot equity per strategy each cycle from the session view`() {
        // #1073: the store fills equity_snapshots only from snapshot.equity; the poller
        // carries the session's per-strategy sample on the same cadence as venue state.
        val now = 1_700_000_000_000L
        val poller =
            BrokerStatePoller(
                brokers = listOf(FakeBroker()),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { listOf("alpha") },
                clock = { now },
                strategyEquity = {
                    listOf(
                        InsightsTranslate.equitySnapshot(
                            ts = now,
                            strategyId = "alpha",
                            realized = BigDecimal("12.5"),
                            unrealized = BigDecimal("-3"),
                            equity = BigDecimal("100009.5"),
                            startingBalance = BigDecimal("100000"),
                        ),
                    )
                },
            )
        poller.pollOnce()

        val body = collectBodies("snapshot.equity")
        assertThat(body).contains("\"type\":\"snapshot.equity\"")
        assertThat(body).contains("\"strategyId\":\"alpha\"")
        assertThat(body).contains("\"startingBalance\":100000")
        assertThat(body).contains("\"equity\":100009.5")
    }

    @Test
    fun `a failing equity reader skips the sample, not the poll`() {
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                clock = { now },
                strategyEquity = { error("engine busy") },
            )
        poller.pollOnce()

        assertThat(broker.accountReads.get()).isEqualTo(1)
    }

    @Test
    fun `closed market polls once per closed interval instead of every cycle`() {
        var now = 1_700_000_000_000L
        val broker = FakeBroker()
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                clock = { now },
                closedPollIntervalMs = 60_000L,
            )
        poller.pollOnce()
        assertThat(broker.accountReads.get()).isEqualTo(1)

        broker.open = false
        repeat(5) {
            now += 10_000L
            poller.pollOnce()
        }
        // One closed-interval heartbeat (at +60s) on top of the open read.
        assertThat(broker.accountReads.get()).isEqualTo(2)

        broker.open = true
        now += 10_000L
        poller.pollOnce()
        assertThat(broker.accountReads.get()).isEqualTo(3)
    }

    @Test
    fun `each cycle announces this session's roster ids, not the attribution set`() {
        val now = 1_700_000_000_000L
        val poller =
            BrokerStatePoller(
                brokers = listOf(FakeBroker()),
                sink = sink,
                attribution = TicketAttribution(),
                // deployedIds is the DSL-name attribution set; rosterIds is the dashboard id form.
                deployedIds = { listOf("gold_eur_rel2_evening_cont8") },
                rosterIds = { listOf("forward_bench:s0", "forward_bench:s1") },
                clock = { now },
            )
        poller.pollOnce()
        val body = collectBodies("instance.roster")
        assertThat(body)
            .contains("instance.roster")
            .contains("forward_bench:s0")
            .contains("forward_bench:s1")
        assertThat(body).doesNotContain("gold_eur_rel2_evening_cont8")
    }

    @Test
    fun `no roster is announced when this session has no roster ids`() {
        val poller =
            BrokerStatePoller(
                brokers = listOf(FakeBroker()),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { listOf("x") },
                rosterIds = { emptyList() },
                clock = { 1_700_000_000_000L },
            )
        poller.pollOnce()
        // Give the sink a moment; the roster envelope must never appear.
        assertThat(collectBodies("state.account")).doesNotContain("instance.roster")
    }

    @Test
    fun `close stops the polling thread`() {
        val broker = FakeBroker()
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                pollIntervalMs = 20L,
            )
        poller.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (broker.accountReads.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertThat(broker.accountReads.get()).isGreaterThanOrEqualTo(2)
        poller.close()
        val after = broker.accountReads.get()
        Thread.sleep(100)
        assertThat(broker.accountReads.get()).isEqualTo(after)
    }
}
