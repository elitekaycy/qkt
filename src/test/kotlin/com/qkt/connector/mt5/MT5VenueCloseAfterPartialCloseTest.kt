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
 * qkt closes part of ticket 999 (a scale-out: 0.04 of 0.10) and the venue confirms it. Shortly
 * after, the venue's own take-profit closes the remaining 0.06. That venue close is not the
 * engine's: it must be booked, whether or not the poller saw the engine's part first.
 */
class MT5VenueCloseAfterPartialCloseTest {
    private val server = MockWebServer()
    private val volume = AtomicReference("0.10")
    private val deals = AtomicReference("[]")
    private val fills = CopyOnWriteArrayList<BrokerEvent.OrderFilled>()
    private lateinit var broker: MT5Broker

    private fun deal(
        ticket: Long,
        volume: String,
        price: String,
    ) = """{"ticket":$ticket,"order":${ticket + 100},"position_id":999,"symbol":"EURUSDm","type":1,"entry":1,""" +
        """"volume":"$volume","price":"$price","magic":10001,"time_msc":1700000000000}"""

    @BeforeEach
    fun setup() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/position_close_partial") -> {
                            volume.set("0.06")
                            deals.set("[${deal(401, "0.04", "1.1050")}]")
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":501,"deal":401,"price":"1.1050",""" +
                                    """"volume":"0.04","comment":"ok"}}""",
                            )
                        }
                        path.startsWith("/get_positions") ->
                            MockResponse().setBody(
                                volume.get()?.let {
                                    """[{"ticket":999,"symbol":"EURUSDm","type":0,"volume":"$it",""" +
                                        """"price_open":"1.1000","sl":"0","tp":"1.1100","profit":"0",""" +
                                        """"magic":10001,"time_msc":1700000000000}]"""
                                } ?: "[]",
                            )
                        path.startsWith("/history_deals_get") -> MockResponse().setBody(deals.get())
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        val clock = FixedClock(time = 1_700_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
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

    private fun scaleOut() {
        broker.submit(
            OrderRequest.Market(
                id = "scale-1",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.04"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
                closesTicket = "999",
                partialClose = true,
            ),
        )
        val deadline = System.currentTimeMillis() + 3_000L
        while (fills.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertThat(fills.single().quantity).isEqualByComparingTo("0.04")
    }

    private fun venueTakeProfitClosesTheRest() {
        volume.set(null)
        deals.set("[${deal(401, "0.04", "1.1050")},${deal(402, "0.06", "1.1100")}]")
    }

    @Test
    fun `a venue close right after a confirmed partial close is booked`() {
        scaleOut()
        broker.poller.tick()
        venueTakeProfitClosesTheRest()
        broker.poller.tick()

        val venueClose = fills.drop(1).single()
        assertThat(venueClose.quantity).isEqualByComparingTo("0.06")
        assertThat(venueClose.price).isEqualByComparingTo("1.1100")
        assertThat(venueClose.updatesOrderExecution).isFalse()
    }

    @Test
    fun `a venue close the poller sees together with the partial close books only the venue part`() {
        scaleOut()
        venueTakeProfitClosesTheRest()
        broker.poller.tick()

        val venueClose = fills.drop(1).single()
        assertThat(venueClose.quantity).isEqualByComparingTo("0.06")
        assertThat(venueClose.price).isEqualByComparingTo("1.1100")
    }

    @Test
    fun `the poller seeing the confirmed partial close books nothing more`() {
        scaleOut()
        broker.poller.tick()
        broker.poller.tick()

        assertThat(fills).hasSize(1)
    }
}
