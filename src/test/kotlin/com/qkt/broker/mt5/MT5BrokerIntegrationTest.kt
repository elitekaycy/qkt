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
    fun `submit pending stop emits accepted but defers filled`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":42,"deal":0,"price":"1.1050","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Stop(
                id = "stop-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isTrue
        // OrderAccepted but no OrderFilled — pending fills arrive via the position poller in Phase 26c.
        assertThat(captured).hasSize(1)
        assertThat(captured[0]).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
    }

    @Test
    fun `pending fill propagates via position poller (phase 26c)`() {
        // Use a fresh broker with a fast poll interval so the test can observe the open detection.
        broker.shutdown()
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100,
            )
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1_700_000_000_000L))

        // Place pending stop
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":777,"deal":0,"price":"1.1050","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Stop(
                id = "stop-26c",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        captured.clear()
        fastBroker.submit(req)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)

        // Now the poller will tick every 100ms — enqueue a `/positions` response with our
        // ticket. The broker's onPendingPositionOpened callback should fire and emit OrderFilled.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":777,"symbol":"EURUSDm","type":0,"volume":"0.1","price_open":"1.1050",""" +
                    """"sl":"0","tp":"0","profit":"0","magic":10001,"open_time":"1700000000","comment":"stop-26c"}]""",
            ),
        )

        // Allow time for the poller to tick at least once
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            captured.none { it is BrokerEvent.OrderFilled && it.clientOrderId == "stop-26c" }
        ) {
            Thread.sleep(50)
        }
        fastBroker.shutdown()

        val filled =
            captured.filterIsInstance<BrokerEvent.OrderFilled>().firstOrNull { it.clientOrderId == "stop-26c" }
                ?: error("OrderFilled with clientOrderId=stop-26c never published; captured=$captured")
        assertThat(filled.brokerOrderId).isEqualTo("777")
        assertThat(filled.symbol).isEqualTo("EURUSD")
        assertThat(filled.side).isEqualTo(Side.BUY)
        assertThat(filled.price).isEqualByComparingTo("1.1050")
        assertThat(filled.strategyId).isEqualTo("s1")
    }

    @Test
    fun `IfTouched is rejected since DSL surface and translator both miss it`() {
        val req =
            OrderRequest.IfTouched(
                id = "it-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                triggerPrice = BigDecimal("1.1050"),
                onTrigger = com.qkt.execution.TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).containsIgnoringCase("does not translate")
    }
}
