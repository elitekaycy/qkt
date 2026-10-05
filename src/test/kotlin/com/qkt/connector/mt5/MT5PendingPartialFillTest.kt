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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A resting order the venue fills in part (a limit order with RETURN filling on an exchange-style
 * symbol) opens position 9001 for 0.04 of 0.10 while order 9001 keeps resting for the other 0.06.
 * The first slice is a partial fill, not the whole order, and the later slices still reach it.
 */
class MT5PendingPartialFillTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private lateinit var bus: EventBus
    private val fills = CopyOnWriteArrayList<BrokerEvent.OrderFilled>()
    private val cancels = CopyOnWriteArrayList<BrokerEvent.OrderCancelled>()
    private val accepts = CopyOnWriteArrayList<BrokerEvent.OrderAccepted>()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        repeat(3) { server.enqueue(MockResponse().setBody("[]")) }
        val clock = FixedClock(time = 1_700_000_000_000L)
        bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        bus.subscribe<BrokerEvent.OrderAccepted> { accepts.add(it) }
        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides =
                    mapOf(
                        "EXNESS:EURUSD" to
                            InstrumentSpec(
                                minVolume = BigDecimal("0.01"),
                                volumeStep = BigDecimal("0.01"),
                                pointSize = BigDecimal("0.00001"),
                                digits = 5,
                                tradeStopsLevelPoints = 0,
                            ),
                    ),
            )
        broker = MT5Broker(profile, bus, clock)
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    private fun restingBuyStop() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":9001,"deal":0,"price":"1.1200","comment":"ok"}}""",
            ),
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
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && accepts.isEmpty()) Thread.sleep(5)
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"4","volume":"0.10","price_open":"1.1200",""" +
                    """"sl":"0","tp":"0","magic":"10001","time_setup":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()
    }

    private fun positions(volume: String) {
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"0","volume":"$volume","price_open":"1.1200",""" +
                    """"sl":"0","tp":"0","profit":"0","magic":"10001","time_msc":"0"}]""",
            ),
        )
        broker.poller.tick()
    }

    @Test
    fun `a resting order filled in part stays working and books every slice`() {
        val partials = CopyOnWriteArrayList<BrokerEvent.OrderPartiallyFilled>()
        restingBuyStop()
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { partials.add(it) }

        positions("0.04")

        assertThat(fills).isEmpty()
        assertThat(partials.single().quantity).isEqualByComparingTo("0.04")
        assertThat(partials.single().cumulativeFilled).isEqualByComparingTo("0.04")

        positions("0.10")

        assertThat(fills.single().quantity).isEqualByComparingTo("0.06")
        assertThat(cancels).isEmpty()
    }

    @Test
    fun `a resting order filled in part whose rest is cancelled keeps the filled part`() {
        val partials = CopyOnWriteArrayList<BrokerEvent.OrderPartiallyFilled>()
        restingBuyStop()
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { partials.add(it) }
        positions("0.04")

        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"0","volume":"0.04","price_open":"1.1200",""" +
                    """"sl":"0","tp":"0","profit":"0","magic":"10001","time_msc":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        assertThat(fills).isEmpty()
        assertThat(partials).hasSize(1)
        assertThat(cancels.single().clientOrderId).isEqualTo("ord-1")
    }
}
