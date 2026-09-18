package com.qkt.observe.insights

import com.qkt.broker.BrokerPendingOrder
import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerAccountStateTest : BrokerStatePollerFixture() {
    @Test
    fun `account and positions ship every cycle with poll-time ids`() {
        var now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.tickets = listOf(ticket("123"))
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                clock = { now },
            )
        poller.pollOnce()
        now += 10_000L
        poller.pollOnce()
        val all = collectBodies("acct-FAKE-1700000010000", "posn-FAKE-1700000010000")
        assertThat(all).contains(""""id":"acct-FAKE-1700000000000"""")
        assertThat(all).contains(""""id":"acct-FAKE-1700000010000"""")
        assertThat(all).contains(""""id":"posn-FAKE-1700000000000"""")
        assertThat(all).contains(""""id":"posn-FAKE-1700000010000"""")
        assertThat(all).contains(""""id":"pord-FAKE-1700000000000"""")
        assertThat(all).contains(""""balance":7824.05""")
        assertThat(all).contains(""""ticket":"123"""")
    }

    @Test
    fun `pending orders ship every cycle with their protective levels and expiry`() {
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.pending =
            listOf(
                BrokerPendingOrder(
                    ticket = "501",
                    symbol = "FAKE:XAUUSD",
                    side = Side.BUY,
                    orderType = "ORDER_TYPE_BUY_LIMIT",
                    qty = BigDecimal("0.01"),
                    price = BigDecimal("2250.0"),
                    stopLoss = BigDecimal("2200.0"),
                    takeProfit = BigDecimal("2400.0"),
                    expiresAt = now + 3_600_000L,
                ),
            )
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                clock = { now },
            )
        poller.pollOnce()
        val all = collectBodies("pord-FAKE-1700000000000")
        assertThat(all).contains(""""type":"state.orders"""")
        assertThat(all).contains(""""ticket":"501"""")
        assertThat(all).contains(""""orderType":"ORDER_TYPE_BUY_LIMIT"""")
        assertThat(all).contains(""""stopLoss":2200.0""")
        assertThat(all).contains(""""expiresAt":1700003600000""")
    }

    @Test
    fun `deal emission is gated by emitDeals while state keeps flowing`() {
        val now = 1_700_000_000_000L
        val broker = FakeBroker()
        broker.allDeals = listOf(deal("1", ts = now - 1_000L))
        val poller =
            BrokerStatePoller(
                brokers = listOf(broker),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                emitDeals = false,
                clock = { now },
            )
        poller.pollOnce()
        val all = collectBodies("posn-FAKE-1700000000000")
        assertThat(all).contains(""""id":"acct-FAKE-1700000000000"""")
        assertThat(all).contains(""""id":"posn-FAKE-1700000000000"""")
        assertThat(all).doesNotContain("deal-FAKE-1")
    }
}
