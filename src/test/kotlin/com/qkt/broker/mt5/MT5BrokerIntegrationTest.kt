package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
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

    // Placement is async (OkHttp dispatcher thread), so events land off the test thread.
    private val captured = java.util.concurrent.CopyOnWriteArrayList<BrokerEvent>()

    /** Poll until [predicate] holds or [timeoutMs] elapses — used to await async venue events. */
    private fun awaitCaptured(
        timeoutMs: Long = 2000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !predicate()) Thread.sleep(5)
    }

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
    fun `GTD expiry stays engine-owned while the gateway ignores expiration`() {
        // The mt5-gateway hardcodes ORDER_TIME_GTC, so the venue never expires a GTD.
        // supportsNativeGtd must stay false or the engine's expiry sweep is disabled
        // and GTD pendings rest forever (#368).
        assertThat(broker.supportsNativeGtd).isFalse()
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
        awaitCaptured { captured.size >= 2 }
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
    fun `submit returns an optimistic ack without blocking on the venue round-trip`() {
        // The gateway response is delayed; submit must return immediately with an optimistic ack
        // (no broker order id yet) and publish events only once the delayed response lands.
        server.enqueue(
            MockResponse()
                .setBody("""{"result":{"retcode":10009,"order":9,"deal":9,"price":"1.1234","comment":"ok"}}""")
                .setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val req =
            OrderRequest.Market(
                id = "ord-async",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        // Returned before the venue responded: optimistic, no broker order id, no events yet.
        assertThat(ack.accepted).isTrue
        assertThat(ack.brokerOrderId).isNull()
        assertThat(captured).isEmpty()
        // The venue result arrives later, as events.
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(1)
    }

    @Test
    fun `submit market with closesTicket closes the position by ticket`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":0,"deal":777,"price":"1.1050","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Market(
                id = "close-1",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
                closesTicket = "424242",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isTrue
        assertThat(ack.brokerOrderId).isEqualTo("424242")
        assertThat(captured).hasSize(2)
        assertThat(captured[0]).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        val filled = captured[1] as BrokerEvent.OrderFilled
        assertThat(filled.clientOrderId).isEqualTo("close-1")
        assertThat(filled.brokerOrderId).isEqualTo("424242")
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.side).isEqualTo(Side.SELL)
        assertThat(filled.price).isEqualByComparingTo("1.1050")
        // The gateway was hit at /close_position with the ticket — NOT /order.
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/close_position")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"position":{"ticket":424242,"volume":0.1}}""")
    }

    @Test
    fun `closesTicket close failure is rejected`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":"Failed to close position"}"""),
        )
        val req =
            OrderRequest.Market(
                id = "close-x",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
                closesTicket = "999",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isFalse
        assertThat(captured.any { it is BrokerEvent.OrderRejected }).isTrue
    }

    @Test
    fun `modifyPosition posts to modify_sl_tp and reports accepted`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":0,"deal":0,"price":"0","comment":"ok"}}""",
            ),
        )
        val ack = broker.modifyPosition("424242", sl = BigDecimal("1.0950"), tp = BigDecimal("1.1100"))
        assertThat(ack.accepted).isTrue
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/modify_sl_tp")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"position":424242,"sl":1.0950,"tp":1.1100}""")
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
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.0500")),
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
        awaitCaptured { captured.size >= 1 }
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
                        path.startsWith("/get_positions") -> {
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
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }
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
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
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
        // Placement is async; wait for the accept so the venue ticket is registered before modify.
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }

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
        assertThat(modifyRequest.path).isEqualTo("/orders/555")
        assertThat(modifyRequest.method).isEqualTo("PUT")
        assertThat(modifyRequest.body.readUtf8()).contains("\"price\":1.1075")
    }

    @Test
    fun `modify with unknown order id is rejected without HTTP call`() {
        val ack = broker.modify("unknown", com.qkt.broker.OrderModification(newStopPrice = BigDecimal("1.0")))
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("no working order")
    }

    @Test
    fun `OCO leg fill propagates via position poller with the leg's clientOrderId`() {
        // Regression test for the v0.26.6 fix. Before that fix, submitComposite never
        // registered per-leg tickets in pendingByTicket, so when an OCO leg filled the
        // position poller's onPendingPositionOpened callback silently returned and the
        // strategy never received the OrderFilled event.
        broker.shutdown()
        val leg1Ticket = 7001L
        val leg2Ticket = 7002L
        val sentOrders =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        var positionsHasFill = false
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> {
                            if (positionsHasFill) {
                                MockResponse().setBody(
                                    """[{"ticket":$leg2Ticket,"symbol":"EURUSDm","type":1,"volume":"0.1",""" +
                                        """"price_open":"1.0950","sl":"0","tp":"0","profit":"0","magic":10001,""" +
                                        """"open_time":"1700000000","comment":"oco:oco-leg-fill/sell-leg"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") -> {
                            val n = sentOrders.incrementAndGet()
                            val ticket = if (n == 1) leg1Ticket else leg2Ticket
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":$ticket,"deal":0,"price":"1.0950","comment":"ok"}}""",
                            )
                        }
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
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1L))

        val buyLeg =
            OrderRequest.Stop(
                id = "buy-leg",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val sellLeg =
            OrderRequest.Stop(
                id = "sell-leg",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.0950"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco-leg-fill",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                leg1 = buyLeg,
                leg2 = sellLeg,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        captured.clear()
        fastBroker.submit(oco)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)

        // Now flip the dispatcher so the SELL leg's position appears. The poller must
        // resolve ticket 7002 back to clientOrderId "sell-leg" — not the parent "oco-leg-fill".
        positionsHasFill = true

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            captured.none { it is BrokerEvent.OrderFilled && it.clientOrderId == "sell-leg" }
        ) {
            Thread.sleep(50)
        }
        fastBroker.shutdown()

        val filled =
            captured.filterIsInstance<BrokerEvent.OrderFilled>().firstOrNull { it.clientOrderId == "sell-leg" }
                ?: error("OrderFilled for sell-leg never published; captured=$captured")
        assertThat(filled.brokerOrderId).isEqualTo(leg2Ticket.toString())
        assertThat(filled.side).isEqualTo(Side.SELL)
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.strategyId).isEqualTo("s1")
    }

    @Test
    fun `price fields are rounded to profile digits before placement`() {
        // XAUUSD has digits=3. An 8-decimal wire price like 4562.16412345 must hit
        // MT5 as 4562.164 — anything more precise gets retcode=10015 INVALID_PRICE.
        broker.shutdown()
        val placedBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") -> {
                            placedBodies.add(request.body.readUtf8())
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"4562.164","comment":"ok"}}""",
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val xauProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:XAUUSD" to TEST_XAUUSD_SPEC),
            )
        val xauBroker = MT5Broker(xauProfile, bus, FixedClock(time = 1L))
        val bracket =
            OrderRequest.Bracket(
                id = "br-xau",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry =
                    OrderRequest.Stop(
                        id = "ent-stop",
                        symbol = "EXNESS:XAUUSD",
                        side = Side.BUY,
                        quantity = BigDecimal("0.10"),
                        stopPrice = BigDecimal("4562.16412345"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1L,
                        strategyId = "s1",
                    ),
                takeProfit = BigDecimal("4574.16412345"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("4544.16412345")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        xauBroker.submit(bracket)
        // Placement is async; wait for the wire to land before shutting the broker down.
        awaitCaptured { placedBodies.isNotEmpty() }
        xauBroker.shutdown()
        assertThat(placedBodies).hasSize(1)
        val body = placedBodies[0]
        assertThat(body).contains("\"price\":4562.164")
        assertThat(body).contains("\"sl\":4544.164")
        assertThat(body).contains("\"tp\":4574.164")
        assertThat(body).doesNotContain("4562.16412345")
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
        val rejection =
            captured
                .filterIsInstance<BrokerEvent.OrderRejected>()
                .firstOrNull { it.clientOrderId == "ord-tiny" }
        assertThat(rejection).isNotNull
    }

    @Test
    fun `bracket with SL too close to entry is rejected pre-placement`() {
        // Configure an override with tradeStopsLevelPoints=100 and pointSize=0.001.
        // Min SL distance: 100 × 0.001 = 0.1.
        // Entry 4561.000, SL 4560.95 → distance 0.05 → reject.
        broker.shutdown()
        val tightProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides =
                    mapOf(
                        "EXNESS:XAUUSD" to
                            InstrumentSpec(
                                minVolume = BigDecimal("0.01"),
                                volumeStep = BigDecimal("0.01"),
                                pointSize = BigDecimal("0.001"),
                                digits = 3,
                                tradeStopsLevelPoints = 100,
                            ),
                    ),
            )
        val tightBroker = MT5Broker(tightProfile, bus, FixedClock(time = 1L))
        val entry =
            OrderRequest.Stop(
                id = "ent",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                stopPrice = BigDecimal("4561.000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "br-tight",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry = entry,
                takeProfit = BigDecimal("4561.500"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("4560.950")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = tightBroker.submit(bracket)
        tightBroker.shutdown()
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("sl too close to entry")
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
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
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
    fun `pending-entry Bracket does not emit synchronous OrderFilled at submit`() {
        // Regression: hedge-straddle wraps each OCO leg in a Bracket over a STOP entry.
        // submitSingle previously treated ANY Bracket as instant-fill (Market-style),
        // publishing a phantom OrderFilled at placement time. That phantom fill marked
        // the OCO siblings FILLED before either was actually dispatched to MT5, so the
        // sibling-cancel path turned into a local state flip — MT5 was never told to
        // cancel the opposing leg and the strategy ran as a hedge rather than an OCO.
        broker.shutdown()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":9001,"deal":0,"price":"1.1050","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val bracket =
            OrderRequest.Bracket(
                id = "br-pending",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry =
                    OrderRequest.Stop(
                        id = "br-pending-entry",
                        symbol = "EXNESS:EURUSD",
                        side = Side.BUY,
                        quantity = BigDecimal("0.10"),
                        stopPrice = BigDecimal("1.1050"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1L,
                        strategyId = "s1",
                    ),
                takeProfit = BigDecimal("1.1080"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.1020")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val pendingBrokerProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val pendingBroker = MT5Broker(pendingBrokerProfile, bus, FixedClock(time = 1L))
        captured.clear()
        val ack = pendingBroker.submit(bracket)
        // Await the async accept before shutting the broker (and its HTTP client) down.
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }
        pendingBroker.shutdown()

        assertThat(ack.accepted).isTrue
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>())
            .withFailMessage(
                "pending-entry Bracket must NOT publish OrderFilled at placement — " +
                    "the gateway only acknowledged the pending order, not a fill",
            ).isEmpty()
    }

    @Test
    fun `pending-entry Bracket emits OrderFilled with bracket-id when position appears`() {
        // Once the pending STOP entry triggers on MT5, the position poller observes a
        // new ticket and the broker must surface that as an OrderFilled keyed by the
        // Bracket's clientOrderId — which is how the OCO sibling lookup in OrderManager
        // resolves the opposing leg to cancel.
        broker.shutdown()
        val ticket = 9101L
        var positionsHasFill = false
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> {
                            if (positionsHasFill) {
                                MockResponse().setBody(
                                    """[{"ticket":$ticket,"symbol":"EURUSDm","type":0,"volume":"0.10",""" +
                                        """"price_open":"1.1050","sl":"1.1020","tp":"1.1080","profit":"0",""" +
                                        """"magic":10001,"open_time":"1700000000","comment":"br-pending"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":$ticket,"deal":0,"price":"1.1050","comment":"ok"}}""",
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
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1L))
        val bracket =
            OrderRequest.Bracket(
                id = "br-pending",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry =
                    OrderRequest.Stop(
                        id = "br-pending-entry",
                        symbol = "EXNESS:EURUSD",
                        side = Side.BUY,
                        quantity = BigDecimal("0.10"),
                        stopPrice = BigDecimal("1.1050"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1L,
                        strategyId = "s1",
                    ),
                takeProfit = BigDecimal("1.1080"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.1020")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        captured.clear()
        fastBroker.submit(bracket)
        // Drop anything produced at submit time — we want to assert the fill that arrives
        // AFTER the venue reports the pending triggered, not the historical phantom fill.
        captured.clear()
        positionsHasFill = true

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            captured.none { it is BrokerEvent.OrderFilled && it.clientOrderId == "br-pending" }
        ) {
            Thread.sleep(50)
        }
        fastBroker.shutdown()

        val filled =
            captured.filterIsInstance<BrokerEvent.OrderFilled>().firstOrNull { it.clientOrderId == "br-pending" }
                ?: error("OrderFilled for bracket-id never published; captured=$captured")
        assertThat(filled.brokerOrderId).isEqualTo(ticket.toString())
        assertThat(filled.side).isEqualTo(Side.BUY)
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.strategyId).isEqualTo("s1")
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

        private val TEST_XAUUSD_SPEC =
            InstrumentSpec(
                minVolume = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.001"),
                digits = 3,
                tradeStopsLevelPoints = 0,
            )
    }
}
