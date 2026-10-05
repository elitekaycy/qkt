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
 * A resting order can fill and be closed (its stop hit, say) between two polls, or while qkt is
 * down: it is then in neither `/orders` nor `/positions`, and only deal history tells a fill from a
 * cancel. E.g. BUY_STOP 9001 fills at 1.1200 and is stopped out at 1.1150 inside one poll interval.
 */
class MT5PendingFilledBetweenPollsTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private val fills = CopyOnWriteArrayList<BrokerEvent.OrderFilled>()
    private val cancels = CopyOnWriteArrayList<BrokerEvent.OrderCancelled>()
    private val accepts = CopyOnWriteArrayList<BrokerEvent.OrderAccepted>()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        repeat(3) { server.enqueue(MockResponse().setBody("[]")) }
        val clock = FixedClock(time = 1_700_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
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

    private fun vanishFromOrdersAndPositions(deals: String) {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody(deals))
        broker.pendingPoller.tickForTesting()
    }

    @Test
    fun `a resting order filled and stopped out between polls books the round trip, not a cancel`() {
        restingBuyStop()

        vanishFromOrdersAndPositions(
            """[{"ticket":7001,"order":9001,"position_id":9001,"symbol":"EURUSDm","type":0,"entry":0,""" +
                """"volume":"0.10","price":"1.1200","commission":"-0.70","magic":10001,"time_msc":0},""" +
                """{"ticket":7002,"order":9002,"position_id":9001,"symbol":"EURUSDm","type":1,"entry":1,""" +
                """"volume":"0.10","price":"1.1150","commission":"-0.70","magic":10001,"time_msc":0,"reason":4}]""",
        )

        assertThat(cancels).isEmpty()
        assertThat(fills).hasSize(2)
        val entry = fills[0]
        assertThat(entry.clientOrderId).isEqualTo("ord-1")
        assertThat(entry.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(entry.side).isEqualTo(Side.BUY)
        assertThat(entry.price).isEqualByComparingTo("1.1200")
        assertThat(entry.quantity).isEqualByComparingTo("0.10")
        assertThat(entry.updatesOrderExecution).isTrue()
        val exit = fills[1]
        assertThat(exit.clientOrderId).isEqualTo("ord-1")
        assertThat(exit.side).isEqualTo(Side.SELL)
        assertThat(exit.price).isEqualByComparingTo("1.1150")
        assertThat(exit.quantity).isEqualByComparingTo("0.10")
        assertThat(exit.updatesOrderExecution).isFalse()
        assertThat(exit.venueCosts).isEqualByComparingTo("1.40")
    }

    @Test
    fun `an unreadable deal history leaves the order working for the next round`() {
        restingBuyStop()
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(500))
        broker.pendingPoller.tickForTesting()

        assertThat(cancels).isEmpty()
        assertThat(fills).isEmpty()
    }

    @Test
    fun `a fill whose close deals are not all in history yet is asked about again`() {
        restingBuyStop()

        vanishFromOrdersAndPositions(
            """[{"ticket":7001,"order":9001,"position_id":9001,"symbol":"EURUSDm","type":0,"entry":0,""" +
                """"volume":"0.10","price":"1.1200","magic":10001,"time_msc":0}]""",
        )

        assertThat(cancels).isEmpty()
        assertThat(fills).isEmpty()
    }

    @Test
    fun `a resting order with no deal is a real cancel`() {
        restingBuyStop()

        vanishFromOrdersAndPositions("[]")

        assertThat(fills).isEmpty()
        assertThat(cancels).hasSize(1)
        assertThat(cancels[0].clientOrderId).isEqualTo("ord-1")
        assertThat(cancels[0].reason).contains("external or gtd-expired")
    }
}
