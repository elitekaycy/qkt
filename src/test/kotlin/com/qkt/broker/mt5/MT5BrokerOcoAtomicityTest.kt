package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MT5BrokerOcoAtomicityTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private lateinit var bus: EventBus
    private val captured = mutableListOf<BrokerEvent>()
    private val placedTickets = CopyOnWriteArrayList<Long>()
    private val cancelledTickets = CopyOnWriteArrayList<Long>()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // State recovery + poller seeds before any test-specific dispatcher is installed.
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))

        val clock = FixedClock(time = 1_700_000_000_000L)
        bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderAccepted> { captured.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { captured.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { captured.add(it) }

        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        broker = MT5Broker(profile, bus, clock)
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    @Test
    fun `OCO rejects atomically when second leg fails — first leg cancelled, no OrderAccepted`() {
        val firstTicket = 8001L
        val orderCalls = AtomicInteger(0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders/") && request.method == "DELETE" -> {
                            val ticket = path.removePrefix("/orders/").toLong()
                            cancelledTickets.add(ticket)
                            MockResponse().setBody("""{"ok":true}""")
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") -> {
                            val n = orderCalls.incrementAndGet()
                            if (n == 1) {
                                placedTickets.add(firstTicket)
                                MockResponse().setBody(
                                    """{"result":{"retcode":10009,"order":$firstTicket,"deal":0,""" +
                                        """"price":"1.0950","comment":"ok"}}""",
                                )
                            } else {
                                MockResponse().setBody(
                                    """{"result":{"retcode":10015,"order":0,"deal":0,""" +
                                        """"price":"0","comment":"INVALID_PRICE"}}""",
                                )
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        val oco = sampleOco("atomic-1")
        val ack = broker.submit(oco)

        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).isNotNull
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).hasSize(1)
        assertThat(cancelledTickets).containsExactly(firstTicket)
    }

    @Test
    fun `OCO rejects atomically when first leg fails — second leg never placed`() {
        val orderCalls = AtomicInteger(0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders/") && request.method == "DELETE" -> {
                            val ticket = path.removePrefix("/orders/").toLong()
                            cancelledTickets.add(ticket)
                            MockResponse().setBody("""{"ok":true}""")
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") -> {
                            orderCalls.incrementAndGet()
                            MockResponse().setBody(
                                """{"result":{"retcode":10015,"order":0,"deal":0,""" +
                                    """"price":"0","comment":"INVALID_PRICE"}}""",
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        val oco = sampleOco("atomic-2")
        val ack = broker.submit(oco)

        assertThat(ack.accepted).isFalse
        assertThat(orderCalls.get()).isEqualTo(1)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).hasSize(1)
        assertThat(cancelledTickets).isEmpty()
    }

    companion object {
        private val TEST_EURUSD_SPEC =
            InstrumentSpec(
                minVolume = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.00001"),
                digits = 5,
                tradeStopsLevelPoints = 0,
            )
    }

    private fun sampleOco(id: String): OrderRequest.StandaloneOCO {
        val leg1 =
            OrderRequest.Stop(
                id = "$id-buy",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val leg2 =
            OrderRequest.Stop(
                id = "$id-sell",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.0950"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        return OrderRequest.StandaloneOCO(
            id = id,
            symbol = "EXNESS:EURUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.1"),
            leg1 = leg1,
            leg2 = leg2,
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s1",
        )
    }
}
