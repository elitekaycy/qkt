package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MT5BrokerIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private lateinit var bus: EventBus
    private val captured = mutableListOf<BrokerEvent>()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // First call from state recovery: empty positions.
        server.enqueue(MockResponse().setBody("[]"))
        // Poller seed: empty positions.
        server.enqueue(MockResponse().setBody("[]"))

        val clock = FixedClock(time = 1_700_000_000_000L)
        bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { e -> captured.add(e) }
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> captured.add(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> captured.add(e) }

        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
            )
        broker = MT5Broker(profile, bus, clock)
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    @Test
    fun `submit market buy emits accepted plus filled`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Market(
                id = "ord-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isTrue
        assertThat(captured).hasSize(2)
        assertThat(captured[0]).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        assertThat(captured[1]).isInstanceOf(BrokerEvent.OrderFilled::class.java)
        val filled = captured[1] as BrokerEvent.OrderFilled
        assertThat(filled.symbol).isEqualTo("EURUSD")
        assertThat(filled.price).isEqualByComparingTo("1.1234")
        // gateway received translated symbol
        server.takeRequest()
        server.takeRequest()
        val recordedOrder = server.takeRequest()
        val body = recordedOrder.body.readUtf8()
        assertThat(body).contains("\"symbol\":\"EURUSDm\"")
        assertThat(body).contains("\"magic\":10001")
    }

    @Test
    fun `bracket submit includes sl tp in payload`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val entry =
            OrderRequest.Market(
                id = "ent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "br-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entry = entry,
                takeProfit = BigDecimal("1.1500"),
                stopLoss = BigDecimal("1.0500"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        broker.submit(bracket)
        server.takeRequest()
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"sl\":1.05")
        assertThat(body).contains("\"tp\":1.15")
    }

    @Test
    fun `non-native order type returns rejection without HTTP call`() {
        val limit =
            OrderRequest.Limit(
                id = "l-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                limitPrice = BigDecimal("1.1000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(limit)
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("not natively")
    }
}
