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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/** [BrokerStatePoller] cycles against a fake broker, through a real sink and HTTP collector. */
abstract class BrokerStatePollerFixture {
    protected lateinit var server: MockWebServer
    protected lateinit var sink: InsightsSink

    protected class FakeBroker : Broker {
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

    protected fun deal(
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

    protected fun ticket(
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
    protected fun collectBodies(vararg markers: String): String {
        val bodies = StringBuilder()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val req = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            bodies.append(req.body.readUtf8())
            if (markers.all { bodies.contains(it) }) break
        }
        return bodies.toString()
    }
}
