package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.broker.BrokerAccountState
import com.qkt.broker.BrokerDeal
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [BrokerStatePoller] cycles against a fake broker, through a real sink and HTTP collector. */
class BrokerStatePollerTest {
    private lateinit var server: MockWebServer
    private lateinit var sink: InsightsSink

    private class FakeBroker : Broker {
        override val name: String = "FAKE"
        override val capabilities: Set<OrderTypeCapability> = emptySet()

        override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, null, accepted = true)

        override fun cancel(orderId: String) {}

        val accountReads = AtomicInteger(0)
        var account: BrokerAccountState? =
            BrokerAccountState(
                broker = "FAKE",
                currency = "USD",
                balance = BigDecimal("7824.05"),
                equity = BigDecimal("7676.54"),
                margin = null,
                marginFree = null,
                openProfit = BigDecimal("-147.51"),
                marginLevel = null,
            )
        var tickets: List<BrokerPositionTicket> = emptyList()
        var allDeals: List<BrokerDeal> = emptyList()

        override fun accountState(): BrokerAccountState? {
            accountReads.incrementAndGet()
            return account
        }

        override fun positionTickets(): List<BrokerPositionTicket> = tickets

        override fun deals(
            from: Long,
            to: Long,
        ): List<BrokerDeal> = allDeals.filter { it.ts in from..to }
    }

    private fun deal(
        ticket: String,
        ts: Long,
        positionTicket: String? = null,
        comment: String? = null,
    ): BrokerDeal =
        BrokerDeal(
            broker = "FAKE",
            dealTicket = ticket,
            positionTicket = positionTicket,
            orderTicket = null,
            symbol = "FAKE:XAUUSD",
            side = Side.BUY,
            entry = "IN",
            qty = BigDecimal("0.01"),
            price = BigDecimal("2300.5"),
            profit = BigDecimal.ZERO,
            commission = BigDecimal.ZERO,
            swap = BigDecimal.ZERO,
            magic = null,
            comment = comment,
            ts = ts,
        )

    private fun ticket(
        id: String,
        comment: String? = null,
    ): BrokerPositionTicket =
        BrokerPositionTicket(
            ticket = id,
            symbol = "FAKE:XAUUSD",
            side = Side.BUY,
            qty = BigDecimal("0.01"),
            entryPrice = BigDecimal("2300.5"),
            currentPrice = null,
            profit = null,
            swap = null,
            openedAt = null,
            comment = comment,
        )

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")
            }
        sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 50L,
                queueCapacity = 1000,
            )
    }

    @AfterEach
    fun teardown() {
        sink.close()
        server.shutdown()
    }

    /** Drains collector requests until [markers] all appear or five seconds pass. */
    private fun collectBodies(vararg markers: String): String {
        val bodies = StringBuilder()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val req = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            bodies.append(req.body.readUtf8())
            if (markers.all { bodies.contains(it) }) break
        }
        return bodies.toString()
    }

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
        assertThat(all).contains(""""balance":7824.05""")
        assertThat(all).contains(""""ticket":"123"""")
    }

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
    fun `attribution map is pruned to the broker's live tickets each cycle`() {
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
        assertThat(attribution.ownerOf("GONE")).isNull()
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
