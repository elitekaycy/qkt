package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
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
 * #1353: MT5 answers a close of ticket 999 (0.10 lots) with retcode 10010 (DONE_PARTIAL) and
 * volume 0.04. Only 0.04 is booked, the 0.06 left at the venue stays an open position, and the
 * rest of the close is sent again; when that cannot finish, the close order ends cancelled.
 */
class MT5PartialCloseAckTest {
    private val server = MockWebServer()
    private val volume = AtomicReference<String?>("0.10")
    private val closeReplies = ConcurrentLinkedQueue<() -> MockResponse>()
    private val closeBodies = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<BrokerEvent>()
    private lateinit var broker: MT5Broker

    private fun done(
        retcode: Int,
        deal: Long,
        lots: String,
        price: String,
        left: String?,
    ): () -> MockResponse =
        {
            volume.set(left)
            MockResponse().setBody(
                """{"result":{"retcode":$retcode,"order":${deal + 100},"deal":$deal,"price":"$price",""" +
                    """"volume":"$lots","comment":"ok"}}""",
            )
        }

    @BeforeEach
    fun setup() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/close_position") || path.startsWith("/position_close_partial") -> {
                            closeBodies.add(path + " " + request.body.readUtf8())
                            closeReplies.poll()?.invoke() ?: MockResponse().setResponseCode(404)
                        }
                        path.startsWith("/get_positions") ->
                            MockResponse().setBody(
                                volume.get()?.let {
                                    """[{"ticket":999,"symbol":"EURUSDm","type":0,"volume":"$it",""" +
                                        """"price_open":"1.1000","sl":"1.0900","tp":"1.1100","profit":"0",""" +
                                        """"magic":10001,"time_msc":1700000000000}]"""
                                } ?: "[]",
                            )
                        path.startsWith("/history_deals_get") || path.startsWith("/orders") ->
                            MockResponse().setBody("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        val clock = FixedClock(time = 1_700_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderCancelled> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { events.add(it) }
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
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    private fun close(
        quantity: String,
        partial: Boolean = false,
    ) {
        broker.submit(
            OrderRequest.Market(
                id = "close-1",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal(quantity),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
                closesTicket = "999",
                partialClose = partial,
            ),
        )
    }

    private fun awaitEnded() {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            events.none { it is BrokerEvent.OrderFilled || it is BrokerEvent.OrderCancelled }
        ) {
            Thread.sleep(5)
        }
    }

    @Test
    fun `a close the venue filled in part books the part and closes the rest`() {
        closeReplies.add(done(10010, deal = 401, lots = "0.04", price = "1.1050", left = "0.06"))
        closeReplies.add(done(10009, deal = 402, lots = "0.06", price = "1.1040", left = null))

        close("0.10")
        awaitEnded()

        val part = events.filterIsInstance<BrokerEvent.OrderPartiallyFilled>().single()
        assertThat(part.clientOrderId).isEqualTo("close-1")
        assertThat(part.quantity).isEqualByComparingTo("0.04")
        assertThat(part.cumulativeFilled).isEqualByComparingTo("0.04")
        assertThat(part.price).isEqualByComparingTo("1.1050")
        val rest = events.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(rest.clientOrderId).isEqualTo("close-1")
        assertThat(rest.quantity).isEqualByComparingTo("0.06")
        assertThat(rest.price).isEqualByComparingTo("1.1040")
        assertThat(closeBodies).hasSize(2)
        assertThat(closeBodies.last()).startsWith("/close_position")

        broker.poller.tick()
        assertThat(events.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(1)
    }

    @Test
    fun `a partial close the venue filled in part sends only what is left of it`() {
        closeReplies.add(done(10010, deal = 401, lots = "0.02", price = "1.1050", left = "0.08"))
        closeReplies.add(done(10009, deal = 402, lots = "0.04", price = "1.1040", left = "0.04"))

        close("0.06", partial = true)
        awaitEnded()

        assertThat(events.filterIsInstance<BrokerEvent.OrderPartiallyFilled>().single().quantity)
            .isEqualByComparingTo("0.02")
        assertThat(events.filterIsInstance<BrokerEvent.OrderFilled>().single().quantity)
            .isEqualByComparingTo("0.04")
        assertThat(closeBodies.last()).isEqualTo("/position_close_partial {\"ticket\":999,\"volume\":0.04}")

        broker.poller.tick()
        assertThat(events.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(1)
    }

    @Test
    fun `a rest the venue refuses to close ends the order and leaves the position open`() {
        closeReplies.add(done(10010, deal = 401, lots = "0.04", price = "1.1050", left = "0.06"))
        closeReplies.add { MockResponse().setResponseCode(400).setBody("""{"error":"retcode 10019 no money"}""") }

        close("0.10")
        awaitEnded()

        assertThat(events.filterIsInstance<BrokerEvent.OrderPartiallyFilled>().single().quantity)
            .isEqualByComparingTo("0.04")
        assertThat(events.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
        assertThat(events.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
        val ended = events.filterIsInstance<BrokerEvent.OrderCancelled>().single()
        assertThat(ended.clientOrderId).isEqualTo("close-1")
        assertThat(ended.reason).contains("0.06")

        // The 0.04 the engine closed is not booked a second time as a venue close.
        broker.poller.tick()
        assertThat(events.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
    }
}
