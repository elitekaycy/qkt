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
        // State recovery: empty positions.
        server.enqueue(MockResponse().setBody("[]"))
        // Position poller seed: empty positions.
        server.enqueue(MockResponse().setBody("[]"))
        // Pending-order poller seed (Phase 26d): empty /orders.
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
    fun `submit market buy emits accepted plus filled`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Market(
                id = "ord-1",
                symbol = "EXNESS:EURUSD",
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
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.price).isEqualByComparingTo("1.1234")
        // gateway received translated symbol — consume the 3 setup calls then the actual order
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed (Phase 26d)
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
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "br-1",
                symbol = "EXNESS:EURUSD",
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
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed (Phase 26d)
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
                symbol = "EXNESS:EURUSD",
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

        // Route /positions and /orders independently so the two pollers don't race on a FIFO queue.
        var positionsHasFill = false
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/positions") -> {
                            if (positionsHasFill) {
                                MockResponse().setBody(
                                    """[{"ticket":777,"symbol":"EURUSDm","type":0,"volume":"0.1",""" +
                                        """"price_open":"1.1050","sl":"0","tp":"0","profit":"0","magic":10001,""" +
                                        """"open_time":"1700000000","comment":"stop-26c"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":777,"deal":0,"price":"1.1050","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1_700_000_000_000L))

        val req =
            OrderRequest.Stop(
                id = "stop-26c",
                symbol = "EXNESS:EURUSD",
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

        // Now flip the dispatcher so /positions returns the new position. The poller
        // observes the open and emits OrderFilled with the original clientOrderId.
        positionsHasFill = true

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
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.side).isEqualTo(Side.BUY)
        assertThat(filled.price).isEqualByComparingTo("1.1050")
        assertThat(filled.strategyId).isEqualTo("s1")
    }

    @Test
    fun `pending disappearing without becoming a position emits OrderCancelled (phase 26d)`() {
        broker.shutdown()

        // Use a path-routing dispatcher so /positions and /orders responses don't conflict.
        // The default queue is FIFO across all paths, which doesn't model the gateway.
        var ordersHasTicket = false
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> {
                            if (ordersHasTicket) {
                                MockResponse().setBody(
                                    """[{"ticket":999,"symbol":"EURUSDm","type":"BUY_STOP","volume":"0.1",""" +
                                        """"price_open":"1.1050","sl":"0","tp":"0","magic":10001,""" +
                                        """"time_setup":"1700000000","time_expiration":"0","comment":"stop-26d-cancel"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":999,"deal":0,"price":"1.1050","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> captured.add(e) }
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1_700_000_000_000L))

        val req =
            OrderRequest.Stop(
                id = "stop-26d-cancel",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        captured.clear()
        fastBroker.submit(req)
        // Pending poller will tick. First make /orders return the ticket so the poller's
        // snapshot picks it up. Wait one poll-cycle, then flip to empty so the next tick
        // sees the disappearance.
        ordersHasTicket = true
        Thread.sleep(300)
        ordersHasTicket = false

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            captured.none { it is BrokerEvent.OrderCancelled && it.clientOrderId == "stop-26d-cancel" }
        ) {
            Thread.sleep(50)
        }
        fastBroker.shutdown()

        val cancelled =
            captured.filterIsInstance<BrokerEvent.OrderCancelled>().firstOrNull {
                it.clientOrderId == "stop-26d-cancel"
            }
                ?: error("OrderCancelled with clientOrderId=stop-26d-cancel never published; captured=$captured")
        assertThat(cancelled.brokerOrderId).isEqualTo("999")
        assertThat(cancelled.reason).contains("external or gtd-expired")
    }

    @Test
    fun `modify a working pending order sends new trigger price (phase 26d)`() {
        // Place a pending first so pendingTickets has an entry
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":555,"deal":0,"price":"1.1050","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Stop(
                id = "stop-modify",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        broker.submit(req)

        // Now modify
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":555,"deal":0,"price":"1.1075","comment":"ok"}}""",
            ),
        )
        val ack = broker.modify("stop-modify", com.qkt.broker.OrderModification(newStopPrice = BigDecimal("1.1075")))
        assertThat(ack.accepted).isTrue
        assertThat(ack.brokerOrderId).isEqualTo("555")

        // Consume setup + placement requests, then assert the modify wire shape
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        server.takeRequest() // POST /order placement
        val modifyRequest = server.takeRequest()
        assertThat(modifyRequest.path).isEqualTo("/modify-order/555")
        assertThat(modifyRequest.body.readUtf8()).contains("\"price\":1.1075")
    }

    @Test
    fun `modify with unknown order id is rejected without HTTP call`() {
        val ack = broker.modify("unknown", com.qkt.broker.OrderModification(newStopPrice = BigDecimal("1.0")))
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("no working order")
    }

    @Test
    fun `volume is quantized down to profile volumeStep before placement`() {
        // 0.1944... lots at step 0.01 must hit the wire as 0.19 — the pa-quant /
        // hedge-straddle sizing footgun that crashed live 02:55 / 09:55 placements.
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":11,"deal":0,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Market(
                id = "ord-quant",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.19444444"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        broker.submit(req)
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"volume\":0.19")
        assertThat(body).doesNotContain("0.1944")
    }

    @Test
    fun `volume below volumeMin is rejected without HTTP placement`() {
        val req =
            OrderRequest.Market(
                id = "ord-tiny",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.005"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("below venue volumeMin")
        val rejection = captured.filterIsInstance<BrokerEvent.OrderRejected>().firstOrNull { it.clientOrderId == "ord-tiny" }
        assertThat(rejection).isNotNull
    }

    @Test
    fun `gateway symbol_info is fetched and cached when no override is configured`() {
        // Fresh broker WITHOUT instrumentOverrides so the broker has to call /symbol_info.
        broker.shutdown()
        var symbolInfoHits = 0
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/symbol_info/") -> {
                            symbolInfoHits++
                            MockResponse().setBody(
                                """{"ask":1.1,"bid":1.0999,"digits":5,"point":0.00001,""" +
                                    """"trade_stops_level":0,"volume_min":0.01,"volume_step":0.01}""",
                            )
                        }
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fetchProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
            )
        val fetchBroker = MT5Broker(fetchProfile, bus, FixedClock(time = 1L))
        repeat(3) { i ->
            fetchBroker.submit(
                OrderRequest.Market(
                    id = "f-$i",
                    symbol = "EXNESS:EURUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("0.19444"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "s1",
                ),
            )
        }
        fetchBroker.shutdown()
        // Three placements but a single /symbol_info fetch — the cache held.
        assertThat(symbolInfoHits).isEqualTo(1)
    }

    @Test
    fun `IfTouched is rejected since DSL surface and translator both miss it`() {
        val req =
            OrderRequest.IfTouched(
                id = "it-1",
                symbol = "EXNESS:EURUSD",
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
}
