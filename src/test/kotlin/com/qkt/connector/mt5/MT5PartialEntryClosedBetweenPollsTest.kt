package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #1354: BUY_STOP 9001 for 0.10 opens position 9001 with 0.04. Before the next poll the other 0.06
 * fills at 1.1210 and the position closes at its 1.1300 target. Deal history holds all three deals;
 * each is booked once, whichever poller sees the change first.
 */
class MT5PartialEntryClosedBetweenPollsTest {
    private val server = MockWebServer()
    private val resting = AtomicBoolean(true)
    private val volume = AtomicReference<String?>(null)
    private val deals = AtomicReference("[]")
    private val fills = CopyOnWriteArrayList<BrokerEvent.OrderFilled>()
    private val partials = CopyOnWriteArrayList<BrokerEvent.OrderPartiallyFilled>()
    private val cancels = CopyOnWriteArrayList<BrokerEvent.OrderCancelled>()
    private lateinit var broker: MT5Broker

    private fun deal(
        ticket: Long,
        side: Int,
        entry: Int,
        lots: String,
        price: String,
    ) = """{"ticket":$ticket,"order":9001,"position_id":9001,"symbol":"EURUSDm","type":$side,"entry":$entry,""" +
        """"volume":"$lots","price":"$price","magic":10001,"time_msc":1700000000000}"""

    @BeforeEach
    fun setup() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/orders") ->
                            MockResponse().setBody(
                                if (resting.get()) {
                                    """[{"ticket":"9001","symbol":"EURUSDm","type":"4","volume":"0.10",""" +
                                        """"price_open":"1.1200","sl":"0","tp":"0","magic":"10001","time_setup":"0"}]"""
                                } else {
                                    "[]"
                                },
                            )
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":9001,"deal":0,"price":"1.1200","comment":"ok"}}""",
                            )
                        path.startsWith("/get_positions") ->
                            MockResponse().setBody(
                                volume.get()?.let {
                                    """[{"ticket":"9001","symbol":"EURUSDm","type":"0","volume":"$it",""" +
                                        """"price_open":"1.1200","sl":"0","tp":"1.1300","profit":"0",""" +
                                        """"magic":"10001","time_msc":"1700000000000"}]"""
                                } ?: "[]",
                            )
                        path.startsWith("/history_deals_get") -> MockResponse().setBody(deals.get())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        val clock = FixedClock(time = 1_700_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { partials.add(it) }
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        val accepted = AtomicBoolean(false)
        bus.subscribe<BrokerEvent.OrderAccepted> { accepted.set(true) }
        broker =
            MT5Broker(
                MT5DefaultProfiles.exness.copy(
                    gatewayUrl = server.url("/").toString().trimEnd('/'),
                    httpTimeoutMs = 2000,
                    retryAttempts = 0,
                    pollIntervalMs = 100_000,
                ),
                bus,
                clock,
            )
        broker.submit(
            OrderRequest.Stop(
                id = "ord-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                stopPrice = BigDecimal("1.1200"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            ),
        )
        val deadline = System.currentTimeMillis() + 2_000L
        while (!accepted.get() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        broker.pendingPoller.tickForTesting()
        volume.set("0.04")
        deals.set("[${deal(1, 0, 0, "0.04", "1.1200")}]")
        broker.poller.tick()
        assertThat(partials.single().quantity).isEqualByComparingTo("0.04")
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    /** Between two polls: the rest of the order fills and [closed] of the position closes. */
    private fun restFillsThenCloses(
        closed: String,
        left: String?,
    ) {
        resting.set(false)
        volume.set(left)
        deals.set(
            "[${deal(1, 0, 0, "0.04", "1.1200")},${deal(2, 0, 0, "0.06", "1.1210")}," +
                "${deal(3, 1, 1, closed, "1.1300")}]",
        )
    }

    private fun assertEveryDealBookedOnce(closed: String) {
        val entry = fills.filter { it.updatesOrderExecution }
        assertThat(entry.single().clientOrderId).isEqualTo("ord-1")
        assertThat(entry.single().quantity).isEqualByComparingTo("0.06")
        assertThat(entry.single().price).isEqualByComparingTo("1.1210")
        val close = fills.filterNot { it.updatesOrderExecution }
        assertThat(close.single().quantity).isEqualByComparingTo(closed)
        assertThat(close.single().price).isEqualByComparingTo("1.1300")
        assertThat(close.single().side).isEqualTo(Side.SELL)
        assertThat(cancels).isEmpty()
        assertThat(partials).hasSize(1)
    }

    @Test
    fun `the position poller seeing the closed position first books the rest of the entry and the whole close`() {
        restFillsThenCloses(closed = "0.10", left = null)

        broker.poller.tick()
        broker.pendingPoller.tickForTesting()
        broker.poller.tick()

        assertEveryDealBookedOnce("0.10")
    }

    @Test
    fun `the order poller seeing the order gone first books the rest of the entry, not a cancel`() {
        restFillsThenCloses(closed = "0.10", left = null)

        broker.pendingPoller.tickForTesting()
        broker.poller.tick()
        broker.poller.tick()

        assertEveryDealBookedOnce("0.10")
    }

    @Test
    fun `a position that shrank after its rest filled books the fill and the real close`() {
        restFillsThenCloses(closed = "0.08", left = "0.02")

        broker.poller.tick()
        broker.pendingPoller.tickForTesting()
        broker.poller.tick()

        assertEveryDealBookedOnce("0.08")
    }
}
