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
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MT5BrokerInterchangeableResolutionTest {
    private lateinit var server: MockWebServer
    private val captured = CopyOnWriteArrayList<BrokerEvent>()
    private val clock = FixedClock(time = 1_700_000_000_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        bus.subscribe<BrokerEvent.OrderAccepted> { captured.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { captured.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { captured.add(it) }
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `identical legs whose sends all timed out each resolve to their own look-alike position`() {
        val posts = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/order") && request.method == "POST" -> {
                            posts.incrementAndGet()
                            MockResponse().setResponseCode(500).setBody("gateway timed out")
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        // Until every leg has been sent the read fails: inconclusive, never a clean
                        // absence, so no leg can be rejected before its look-alikes are visible.
                        path.startsWith("/get_positions") ->
                            if (posts.get() < LEGS.size) {
                                MockResponse().setResponseCode(503).setBody("busy")
                            } else {
                                MockResponse().setBody(LOOK_ALIKES)
                            }
                        path.startsWith("/history_deals_get") -> MockResponse().setBody("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val broker = newBroker()

        LEGS.forEach { broker.submit(leg(it)) }
        awaitCaptured { captured.filterIsInstance<BrokerEvent.OrderFilled>().size == LEGS.size }
        broker.shutdown()

        val fills = captured.filterIsInstance<BrokerEvent.OrderFilled>().associateBy { it.clientOrderId }
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
        assertThat(fills.mapValues { it.value.brokerOrderId })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(LEGS[0] to "3270423617", LEGS[1] to "3270424046", LEGS[2] to "3270424108"),
            )
        assertThat(fills.getValue(LEGS[2]).price).isEqualByComparingTo("1.10050")
        assertThat(posts.get()).isEqualTo(LEGS.size)
    }

    private fun awaitCaptured(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline && !predicate()) Thread.sleep(5)
    }

    private fun newBroker(): MT5Broker =
        MT5Broker(
            profile =
                MT5DefaultProfiles.exness.copy(
                    gatewayUrl = server.url("/").toString().trimEnd('/'),
                    httpTimeoutMs = 3000,
                    retryAttempts = 0,
                    pollIntervalMs = 100_000,
                    instrumentOverrides = mapOf("EXNESS:EURUSD" to EURUSD_SPEC),
                ),
            bus = bus,
            clock = clock,
            unknownResolveBackoffMs = 1L,
            unknownPeriodicResolveMs = 20L,
        )

    private fun leg(id: String): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = "EXNESS:EURUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "gold_scale_burst_fixed",
        )

    companion object {
        private val LEGS = (7..9).map { "dsl-gold_scale_burst_fixed--8-stack-tier$it-entry" }

        private val LOOK_ALIKES =
            listOf(3270423617L to "1.10010", 3270424046L to "1.10030", 3270424108L to "1.10050")
                .mapIndexed { i, (ticket, price) ->
                    """{"ticket":$ticket,"symbol":"EURUSDm","type":0,"volume":"0.10","price_open":"$price",""" +
                        """"sl":"0","tp":"0","profit":"0","magic":10001,"time_msc":${1_700_000_000_100L + i * 100},""" +
                        """"comment":"dsl-gold_scale_burst_fixe"}"""
                }.joinToString(",", "[", "]")

        private val EURUSD_SPEC =
            InstrumentSpec(
                minVolume = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.00001"),
                digits = 5,
                tradeStopsLevelPoints = 0,
                maxVolume = BigDecimal("100"),
            )
    }
}
