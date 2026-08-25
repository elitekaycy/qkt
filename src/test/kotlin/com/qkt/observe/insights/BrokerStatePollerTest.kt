package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.broker.BrokerAccountState
import com.qkt.broker.BrokerDeal
import com.qkt.broker.BrokerPendingOrder
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
                login = 435898347L,
                server = "Exness-MT5Trial9",
                name = "qkt-hedge-straddle",
            )
        var tickets: List<BrokerPositionTicket> = emptyList()
        var allDeals: List<BrokerDeal> = emptyList()
        var pending: List<BrokerPendingOrder> = emptyList()
        var ignoreDealRange: Boolean = false
        var open: Boolean = true

        override fun marketOpen(nowMs: Long): Boolean = open

        override fun accountState(): BrokerAccountState? {
            accountReads.incrementAndGet()
            return account
        }

        override fun positionTickets(): List<BrokerPositionTicket> = tickets

        override fun pendingOrders(): List<BrokerPendingOrder> = pending

        val dealCalls = mutableListOf<Pair<Long, Long>>()
        var failDeals: Boolean = false

        override fun deals(
            from: Long,
            to: Long,
        ): List<BrokerDeal> {
            dealCalls.add(from to to)
            check(!failDeals) { "deal fetch failed" }
            return if (ignoreDealRange) allDeals else allDeals.filter { it.ts in from..to }
        }
    }

    private fun deal(
        ticket: String,
        ts: Long,
        positionTicket: String? = null,
        comment: String? = null,
        entry: String = "IN",
    ): BrokerDeal =
        BrokerDeal(
            broker = "FAKE",
            dealTicket = ticket,
            positionTicket = positionTicket,
            orderTicket = null,
            symbol = "FAKE:XAUUSD",
            side = Side.BUY,
            entry = entry,
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
    fun `deployed strategy poller emits only locally attributed broker deals`() {
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

        val all = collectBodies("deal-FAKE-local-ticket", "deal-FAKE-local-comment")
        assertThat(all)
            .contains("deal-FAKE-local-ticket")
            .contains("deal-FAKE-local-comment")
            .doesNotContain("deal-FAKE-foreign-ticket")
            .doesNotContain("deal-FAKE-unknown")
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
